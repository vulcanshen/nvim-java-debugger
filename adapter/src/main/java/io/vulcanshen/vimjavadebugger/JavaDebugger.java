package io.vulcanshen.vimjavadebugger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.StepRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心 debugger — 透過 JDI 與目標 JVM 溝通。
 */
public class JavaDebugger {

    private final DapServer server;
    private final String projectRoot;
    private final ProjectDetector.ProjectType projectType;

    private VirtualMachine vm;
    private Process targetProcess;
    private volatile long stoppedThreadId = -1;
    private final Map<String, List<BreakpointRequest>> breakpoints = new HashMap<>();
    // 暫存尚未 verified 的斷點（class 還沒載入），等 ClassPrepareEvent 時再設
    private final Map<String, Map<Integer, String>> pendingBreakpoints = new HashMap<>();

    public JavaDebugger(DapServer server, String projectRoot, ProjectDetector.ProjectType projectType) {
        this.server = server;
        this.projectRoot = projectRoot;
        this.projectType = projectType;
    }

    public void launch(String mainClass) throws Exception {
        int port = findFreePort();
        System.err.println("Using debug port: " + port);

        targetProcess = startTargetJvm(mainClass, port);
        startOutputForwarding();
        vm = attachToJvm(port);
        startEventLoop();
    }

    private int findFreePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Process startTargetJvm(String mainClass, int port) throws Exception {
        String debugArgs = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + port;

        ProcessBuilder pb;

        switch (projectType) {
            case MAVEN: {
                String mvnCmd = getMavenCommand();

                // 1. mvn compile
                System.err.println("Maven: compiling...");
                Process compile = new ProcessBuilder(mvnCmd, "compile", "-q")
                    .directory(new File(projectRoot))
                    .redirectErrorStream(true)
                    .start();
                String compileOutput = new String(compile.getInputStream().readAllBytes());
                int compileExitCode = compile.waitFor();
                if (compileExitCode != 0) {
                    throw new RuntimeException("Maven compile failed:\n" + compileOutput);
                }

                // 2. mvn dependency:build-classpath 取得 dependency classpath
                System.err.println("Maven: resolving dependencies...");
                Process cpProc = new ProcessBuilder(mvnCmd, "dependency:build-classpath",
                        "-Dmdep.outputFile=/dev/stdout", "-q")
                    .directory(new File(projectRoot))
                    .start();
                String depClasspath = new String(cpProc.getInputStream().readAllBytes()).trim();
                cpProc.waitFor();

                // 組合 classpath: target/classes + dependencies
                String targetClasses = new File(projectRoot, "target/classes").getAbsolutePath();
                String fullClasspath = targetClasses;
                if (!depClasspath.isEmpty()) {
                    fullClasspath = targetClasses + File.pathSeparator + depClasspath;
                }

                // 3. java -cp 啟動
                if (mainClass == null || mainClass.isEmpty()) {
                    throw new RuntimeException(
                        "mainClass is required for Maven projects. "
                        + "Open a Java file with a main method before starting the debugger.");
                }
                System.err.println("Launching: java " + debugArgs + " -cp ... " + mainClass);
                pb = new ProcessBuilder("java", debugArgs, "-cp", fullClasspath, mainClass);
                break;
            }
            case GRADLE: {
                String gradleCmd = getGradleCommand();

                // 1. gradle classes
                System.err.println("Gradle: compiling...");
                Process compile = new ProcessBuilder(gradleCmd, "classes", "-q")
                    .directory(new File(projectRoot))
                    .redirectErrorStream(true)
                    .start();
                String compileOutput = new String(compile.getInputStream().readAllBytes());
                int compileExitCode = compile.waitFor();
                if (compileExitCode != 0) {
                    throw new RuntimeException("Gradle compile failed:\n" + compileOutput);
                }

                // 2. 用 init script 取得 runtime classpath
                System.err.println("Gradle: resolving classpath...");
                File initScript = createGradleInitScript();
                Process cpProc = new ProcessBuilder(gradleCmd, "-q",
                        "--init-script", initScript.getAbsolutePath(), "printClasspath")
                    .directory(new File(projectRoot))
                    .start();
                String depClasspath = new String(cpProc.getInputStream().readAllBytes()).trim();
                cpProc.waitFor();
                initScript.delete();

                if (depClasspath.isEmpty()) {
                    throw new RuntimeException("Failed to resolve Gradle classpath.");
                }

                // 3. java -cp 啟動
                if (mainClass == null || mainClass.isEmpty()) {
                    throw new RuntimeException(
                        "mainClass is required for Gradle projects. "
                        + "Open a Java file with a main method before starting the debugger.");
                }
                System.err.println("Launching: java " + debugArgs + " -cp ... " + mainClass);
                pb = new ProcessBuilder("java", debugArgs, "-cp", depClasspath, mainClass);
                break;
            }
            case SINGLE_FILE:
            default:
                String javaFile = mainClass != null ? mainClass : findMainJavaFile();
                // 確保有 .java 副檔名（javac 需要）
                if (!javaFile.endsWith(".java")) {
                    javaFile = javaFile + ".java";
                }
                // 確保檔案存在
                File sourceFile = new File(projectRoot, javaFile);
                if (!sourceFile.exists()) {
                    throw new RuntimeException("Source file not found: " + sourceFile.getAbsolutePath());
                }

                // 編譯到隱藏目錄，避免污染使用者的專案目錄
                File buildDir = new File(projectRoot, ".vim-java-debugger/build");
                buildDir.mkdirs();

                String className = javaFile.replace(".java", "").replace(File.separator, ".");
                File classFile = new File(buildDir, className.replace(".", File.separator) + ".class");

                // 只在 .java 比 .class 新時才重新編譯
                if (!classFile.exists() || sourceFile.lastModified() > classFile.lastModified()) {
                    System.err.println("Compiling: javac -g -d " + buildDir.getPath() + " " + javaFile);
                    Process compile = new ProcessBuilder("javac", "-g", "-d", buildDir.getAbsolutePath(), javaFile)
                        .directory(new File(projectRoot))
                        .redirectErrorStream(true)
                        .start();
                    String compileOutput = new String(compile.getInputStream().readAllBytes());
                    int compileExitCode = compile.waitFor();
                    if (compileExitCode != 0) {
                        throw new RuntimeException("Compilation failed:\n" + compileOutput);
                    }
                } else {
                    System.err.println("Skipping compilation: " + javaFile + " is up to date");
                }

                System.err.println("Launching: java " + debugArgs + " -cp " + buildDir.getAbsolutePath() + " " + className);
                pb = new ProcessBuilder("java", debugArgs, "-cp", buildDir.getAbsolutePath(), className);
                break;
        }

        pb.directory(new File(projectRoot));
        return pb.start();
    }

    private VirtualMachine attachToJvm(int port) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager()
            .attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("SocketAttach connector not found"));

        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("localhost");
        args.get("port").setValue(String.valueOf(port));

        // 重試 JDI attach，等待目標 JVM 準備好
        int retries = 60;
        while (retries-- > 0) {
            if (!targetProcess.isAlive()) {
                throw new RuntimeException("Target process exited before debug port was ready.");
            }
            try {
                return connector.attach(args);
            } catch (Exception e) {
                Thread.sleep(500);
            }
        }
        throw new RuntimeException("Timeout waiting for JVM debug connection on port " + port);
    }

    private void startOutputForwarding() {
        // stdout 轉發
        Thread outputThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(targetProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    JsonObject body = new JsonObject();
                    body.addProperty("category", "stdout");
                    body.addProperty("output", line + "\n");
                    server.sendEvent("output", body);
                }
            } catch (Exception e) {
                // process 結束時會自然中斷
            }
        }, "output-forwarding");
        outputThread.setDaemon(true);
        outputThread.start();

        // stderr 轉發
        Thread errorThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(targetProcess.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    JsonObject body = new JsonObject();
                    body.addProperty("category", "stderr");
                    body.addProperty("output", line + "\n");
                    server.sendEvent("output", body);
                }
            } catch (Exception e) {
                // process 結束時會自然中斷
            }
        }, "error-forwarding");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    private volatile boolean terminated = false;

    private void sendTerminated() {
        if (!terminated) {
            terminated = true;
            disconnect();
            JsonObject exitBody = new JsonObject();
            exitBody.addProperty("exitCode", 0);
            server.sendEvent("exited", exitBody);
            server.sendEvent("terminated", null);
            System.exit(0);
        }
    }

    private void startEventLoop() {
        Thread eventThread = new Thread(() -> {
            try {
                EventQueue eventQueue = vm.eventQueue();
                while (true) {
                    EventSet eventSet = eventQueue.remove();
                    for (Event event : eventSet) {
                        handleEvent(event);
                    }
                }
            } catch (InterruptedException | VMDisconnectedException e) {
                sendTerminated();
            }
        }, "jdi-event-loop");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    private void handleEvent(Event event) {
        if (event instanceof ClassPrepareEvent) {
            ClassPrepareEvent cpEvent = (ClassPrepareEvent) event;
            String className = cpEvent.referenceType().name();
            // 檢查是否有等待這個 class 的斷點
            for (Map.Entry<String, Map<Integer, String>> entry : pendingBreakpoints.entrySet()) {
                String sourcePath = entry.getKey();
                Map<Integer, String> lines = entry.getValue();
                List<Integer> resolved = new ArrayList<>();
                for (Map.Entry<Integer, String> lineEntry : lines.entrySet()) {
                    if (lineEntry.getValue().equals(className)) {
                        try {
                            List<Location> locations = cpEvent.referenceType().locationsOfLine(lineEntry.getKey());
                            if (!locations.isEmpty()) {
                                BreakpointRequest bpReq = vm.eventRequestManager()
                                    .createBreakpointRequest(locations.get(0));
                                bpReq.enable();
                                breakpoints.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(bpReq);
                                resolved.add(lineEntry.getKey());

                                // 通知 client 斷點已 verified
                                JsonObject bpBody = new JsonObject();
                                JsonObject bpObj = new JsonObject();
                                bpObj.addProperty("verified", true);
                                bpObj.addProperty("line", lineEntry.getKey());
                                bpBody.add("breakpoint", bpObj);
                                bpBody.addProperty("reason", "changed");
                                server.sendEvent("breakpoint", bpBody);
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to set deferred breakpoint: " + e.getMessage());
                        }
                    }
                }
                for (int line : resolved) {
                    lines.remove(line);
                }
            }
            // resume VM 讓程式繼續跑（用 vm.resume 確保所有 thread 都恢復）
            vm.resume();

        } else if (event instanceof BreakpointEvent) {
            if (terminated) return;
            BreakpointEvent bpEvent = (BreakpointEvent) event;
            stoppedThreadId = bpEvent.thread().uniqueID();
            JsonObject body = new JsonObject();
            body.addProperty("reason", "breakpoint");
            body.addProperty("threadId", stoppedThreadId);
            server.sendEvent("stopped", body);

        } else if (event instanceof StepEvent) {
            if (terminated) return;
            StepEvent stepEvent = (StepEvent) event;
            vm.eventRequestManager().deleteEventRequest(event.request());
            stoppedThreadId = stepEvent.thread().uniqueID();
            JsonObject body = new JsonObject();
            body.addProperty("reason", "step");
            body.addProperty("threadId", stoppedThreadId);
            server.sendEvent("stopped", body);

        } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            sendTerminated();
        }
    }

    public JsonObject setBreakpoints(JsonObject args) {
        String sourcePath = args.getAsJsonObject("source").get("path").getAsString();
        JsonArray bpArray = args.getAsJsonArray("breakpoints");

        List<BreakpointRequest> oldBps = breakpoints.remove(sourcePath);
        if (oldBps != null) {
            for (BreakpointRequest bp : oldBps) {
                vm.eventRequestManager().deleteEventRequest(bp);
            }
        }

        JsonArray resultBps = new JsonArray();
        List<BreakpointRequest> newBps = new ArrayList<>();
        java.util.Set<String> prepareRequestedClasses = new java.util.HashSet<>();

        for (int i = 0; i < bpArray.size(); i++) {
            int line = bpArray.get(i).getAsJsonObject().get("line").getAsInt();
            JsonObject bp = new JsonObject();
            bp.addProperty("verified", false);
            bp.addProperty("line", line);

            try {
                String className = sourcePathToClassName(sourcePath);
                List<ReferenceType> classes = vm.classesByName(className);
                if (!classes.isEmpty()) {
                    List<Location> locations = classes.get(0).locationsOfLine(line);
                    if (!locations.isEmpty()) {
                        BreakpointRequest bpReq = vm.eventRequestManager()
                            .createBreakpointRequest(locations.get(0));
                        bpReq.enable();
                        newBps.add(bpReq);
                        bp.addProperty("verified", true);
                    }
                } else {
                    // Class 尚未載入，註冊 ClassPrepareRequest 等待載入
                    pendingBreakpoints
                        .computeIfAbsent(sourcePath, k -> new HashMap<>())
                        .put(line, className);
                    // 同一個 class 只建一個 ClassPrepareRequest
                    if (!prepareRequestedClasses.contains(className)) {
                        prepareRequestedClasses.add(className);
                        ClassPrepareRequest cpReq = vm.eventRequestManager()
                            .createClassPrepareRequest();
                        cpReq.addClassFilter(className);
                        cpReq.enable();
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to set breakpoint: " + e.getMessage());
            }

            resultBps.add(bp);
        }

        breakpoints.put(sourcePath, newBps);

        JsonObject body = new JsonObject();
        body.add("breakpoints", resultBps);
        return body;
    }

    public JsonObject getThreads() {
        JsonArray threads = new JsonArray();
        if (vm != null) {
            for (ThreadReference thread : vm.allThreads()) {
                JsonObject t = new JsonObject();
                t.addProperty("id", thread.uniqueID());
                t.addProperty("name", thread.name());
                threads.add(t);
            }
        }
        JsonObject body = new JsonObject();
        body.add("threads", threads);
        return body;
    }

    public JsonObject getStackTrace(JsonObject args) {
        long threadId = args.get("threadId").getAsLong();
        JsonArray frames = new JsonArray();

        try {
            ThreadReference thread = findThread(threadId);
            if (thread != null) {
                int frameId = 0;
                for (StackFrame frame : thread.frames()) {
                    Location loc = frame.location();
                    JsonObject f = new JsonObject();
                    f.addProperty("id", frameId++);
                    f.addProperty("name", loc.method().name());
                    f.addProperty("line", loc.lineNumber());
                    f.addProperty("column", 1);

                    JsonObject source = new JsonObject();
                    source.addProperty("name", loc.sourceName());
                    try {
                        String sourcePath = loc.sourcePath();
                        String resolved = resolveSourcePath(sourcePath);
                        if (resolved != null) {
                            source.addProperty("path", resolved);
                        }
                    } catch (AbsentInformationException e) {
                        // source path 不可用
                    }
                    f.add("source", source);

                    frames.add(f);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get stack trace: " + e.getMessage());
        }

        JsonObject body = new JsonObject();
        body.add("stackFrames", frames);
        return body;
    }

    public JsonObject getScopes(JsonObject args) {
        int frameId = args.get("frameId").getAsInt();
        JsonArray scopes = new JsonArray();

        JsonObject localScope = new JsonObject();
        localScope.addProperty("name", "Local");
        localScope.addProperty("variablesReference", frameId + 1);
        localScope.addProperty("expensive", false);
        scopes.add(localScope);

        JsonObject body = new JsonObject();
        body.add("scopes", scopes);
        return body;
    }

    public JsonObject getVariables(JsonObject args) {
        int variablesReference = args.get("variablesReference").getAsInt();
        int frameId = variablesReference - 1;
        JsonArray variables = new JsonArray();

        try {
            ThreadReference thread = findThread(stoppedThreadId);
            if (thread != null && thread.isSuspended() && thread.frameCount() > frameId) {
                StackFrame frame = thread.frame(frameId);
                List<LocalVariable> visibleVars = frame.visibleVariables();
                for (LocalVariable var : visibleVars) {
                    Value value = frame.getValue(var);
                    JsonObject v = new JsonObject();
                    v.addProperty("name", var.name());
                    v.addProperty("value", value != null ? value.toString() : "null");
                    v.addProperty("type", var.typeName());
                    v.addProperty("variablesReference", 0);
                    variables.add(v);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get variables: " + e.getMessage());
        }

        JsonObject body = new JsonObject();
        body.add("variables", variables);
        return body;
    }

    public void configurationDone() {
        // client 設定完成，resume VM 讓程式開始跑
        if (vm != null) {
            vm.resume();
        }
    }

    public void resume() {
        if (vm != null) {
            vm.resume();
        }
    }

    public void stepOver(long threadId) {
        createStepRequest(threadId, StepRequest.STEP_OVER);
    }

    public void stepIn(long threadId) {
        createStepRequest(threadId, StepRequest.STEP_INTO);
    }

    public void stepOut(long threadId) {
        createStepRequest(threadId, StepRequest.STEP_OUT);
    }

    private void createStepRequest(long threadId, int depth) {
        try {
            ThreadReference thread = findThread(threadId);
            if (thread != null && thread.isSuspended()) {
                StepRequest stepReq = vm.eventRequestManager()
                    .createStepRequest(thread, StepRequest.STEP_LINE, depth);
                stepReq.addCountFilter(1);
                stepReq.enable();
                vm.resume();
            }
        } catch (Exception e) {
            System.err.println("Step failed: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception e) {
                // ignore
            }
            vm = null;
        }
        if (targetProcess != null) {
            targetProcess.destroyForcibly();
            try {
                targetProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignore
            }
            targetProcess = null;
        }
    }

    private ThreadReference findThread(long threadId) {
        if (vm == null) return null;
        for (ThreadReference t : vm.allThreads()) {
            if (t.uniqueID() == threadId) {
                return t;
            }
        }
        return null;
    }

    private String resolveSourcePath(String relativePath) {
        // 嘗試在專案中找到對應的原始碼檔案
        String[] searchDirs = {"src/main/java", "src/test/java", "src", ""};
        for (String dir : searchDirs) {
            File candidate = dir.isEmpty()
                ? new File(projectRoot, relativePath)
                : new File(new File(projectRoot, dir), relativePath);
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        // fallback: 檢查直接拼接是否存在（JDK 內部 class 不會存在）
        File fallback = new File(projectRoot, relativePath);
        return fallback.exists() ? fallback.getAbsolutePath() : null;
    }

    private String sourcePathToClassName(String sourcePath) {
        String path = sourcePath;

        String[] markers = {"src/main/java/", "src/test/java/", "src/"};
        for (String marker : markers) {
            int idx = path.indexOf(marker);
            if (idx >= 0) {
                path = path.substring(idx + marker.length());
                return path.replace(".java", "").replace(File.separator, ".").replace("/", ".");
            }
        }

        // 單一檔案：直接取檔名（去掉路徑和 .java）
        File file = new File(sourcePath);
        return file.getName().replace(".java", "");
    }

    private String findMainJavaFile() throws Exception {
        File root = new File(projectRoot);
        File[] javaFiles = root.listFiles((dir, name) -> name.endsWith(".java"));
        if (javaFiles != null && javaFiles.length > 0) {
            return javaFiles[0].getName();
        }
        throw new RuntimeException("No .java file found in " + projectRoot);
    }


    private String getMavenCommand() throws Exception {
        File wrapper = new File(projectRoot, "mvnw");
        if (wrapper.exists()) {
            return wrapper.getAbsolutePath();
        }
        // 檢查系統是否有 mvn
        try {
            Process check = new ProcessBuilder("mvn", "--version")
                .redirectErrorStream(true).start();
            check.getInputStream().readAllBytes();
            check.waitFor();
            if (check.exitValue() == 0) {
                return "mvn";
            }
        } catch (Exception e) {
            // mvn not found
        }
        throw new RuntimeException(
            "Maven not found. Install Maven or add Maven Wrapper (mvnw) to your project.");
    }

    private String getGradleCommand() throws Exception {
        File wrapper = new File(projectRoot, "gradlew");
        if (wrapper.exists()) {
            return wrapper.getAbsolutePath();
        }
        try {
            Process check = new ProcessBuilder("gradle", "--version")
                .redirectErrorStream(true).start();
            check.getInputStream().readAllBytes();
            check.waitFor();
            if (check.exitValue() == 0) {
                return "gradle";
            }
        } catch (Exception e) {
            // gradle not found
        }
        throw new RuntimeException(
            "Gradle not found. Install Gradle or add Gradle Wrapper (gradlew) to your project.");
    }

    private File createGradleInitScript() throws Exception {
        File initScript = new File(projectRoot, ".vim-java-debugger/init.gradle");
        initScript.getParentFile().mkdirs();
        try (java.io.FileWriter fw = new java.io.FileWriter(initScript)) {
            fw.write(
                "allprojects {\n" +
                "    task printClasspath {\n" +
                "        doLast {\n" +
                "            def cp = []\n" +
                "            try {\n" +
                "                cp.addAll(sourceSets.main.runtimeClasspath.files.collect { it.absolutePath })\n" +
                "            } catch (Exception e) {\n" +
                "                // sourceSets not available\n" +
                "            }\n" +
                "            println cp.join(File.pathSeparator)\n" +
                "        }\n" +
                "    }\n" +
                "}\n"
            );
        }
        return initScript;
    }
}
