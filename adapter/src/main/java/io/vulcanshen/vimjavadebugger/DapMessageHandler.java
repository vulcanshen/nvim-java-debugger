package io.vulcanshen.vimjavadebugger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 處理 DAP 訊息，分派到對應的操作。
 */
public class DapMessageHandler {

    private final DapServer server;
    private JavaDebugger debugger;

    public DapMessageHandler(DapServer server) {
        this.server = server;
    }

    public void handle(JsonObject request) {
        String command = request.get("command").getAsString();

        switch (command) {
            case "initialize":
                handleInitialize(request);
                break;
            case "launch":
                handleLaunch(request);
                break;
            case "setBreakpoints":
                handleSetBreakpoints(request);
                break;
            case "threads":
                handleThreads(request);
                break;
            case "stackTrace":
                handleStackTrace(request);
                break;
            case "scopes":
                handleScopes(request);
                break;
            case "variables":
                handleVariables(request);
                break;
            case "continue":
                handleContinue(request);
                break;
            case "next":
                handleNext(request);
                break;
            case "stepIn":
                handleStepIn(request);
                break;
            case "stepOut":
                handleStepOut(request);
                break;
            case "disconnect":
                handleDisconnect(request);
                break;
            case "configurationDone":
                handleConfigurationDone(request);
                break;
            default:
                server.sendResponse(request, null);
                break;
        }
    }

    private void handleInitialize(JsonObject request) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("supportsConfigurationDoneRequest", true);
        capabilities.addProperty("supportsSingleThreadExecutionRequests", true);
        server.sendResponse(request, capabilities);
        server.sendEvent("initialized", null);
    }

    private void handleLaunch(JsonObject request) {
        JsonObject args = request.getAsJsonObject("arguments");
        String projectRoot = args.has("projectRoot") ? args.get("projectRoot").getAsString() : ".";
        String mainClass = args.has("mainClass") ? args.get("mainClass").getAsString() : null;

        try {
            ProjectDetector detector = new ProjectDetector(projectRoot);
            ProjectDetector.ProjectType type = detector.detect();

            debugger = new JavaDebugger(server, projectRoot, type);
            debugger.launch(mainClass);

            server.sendResponse(request, null);
        } catch (Exception e) {
            System.err.println("Launch failed: " + e.getMessage());
            server.sendErrorResponse(request, e.getMessage());
        }
    }

    private void handleSetBreakpoints(JsonObject request) {
        if (debugger != null) {
            JsonObject body = debugger.setBreakpoints(request.getAsJsonObject("arguments"));
            server.sendResponse(request, body);
        } else {
            server.sendResponse(request, new JsonObject());
        }
    }

    private void handleThreads(JsonObject request) {
        if (debugger != null) {
            JsonObject body = debugger.getThreads();
            server.sendResponse(request, body);
        } else {
            JsonObject body = new JsonObject();
            body.add("threads", new JsonArray());
            server.sendResponse(request, body);
        }
    }

    private void handleStackTrace(JsonObject request) {
        if (debugger != null) {
            JsonObject body = debugger.getStackTrace(request.getAsJsonObject("arguments"));
            server.sendResponse(request, body);
        } else {
            server.sendResponse(request, new JsonObject());
        }
    }

    private void handleScopes(JsonObject request) {
        if (debugger != null) {
            JsonObject body = debugger.getScopes(request.getAsJsonObject("arguments"));
            server.sendResponse(request, body);
        } else {
            server.sendResponse(request, new JsonObject());
        }
    }

    private void handleVariables(JsonObject request) {
        if (debugger != null) {
            JsonObject body = debugger.getVariables(request.getAsJsonObject("arguments"));
            server.sendResponse(request, body);
        } else {
            server.sendResponse(request, new JsonObject());
        }
    }

    private void handleContinue(JsonObject request) {
        if (debugger != null) {
            debugger.resume();
        }
        server.sendResponse(request, null);
    }

    private void handleNext(JsonObject request) {
        if (debugger != null) {
            long threadId = request.getAsJsonObject("arguments").get("threadId").getAsLong();
            debugger.stepOver(threadId);
        }
        server.sendResponse(request, null);
    }

    private void handleStepIn(JsonObject request) {
        if (debugger != null) {
            long threadId = request.getAsJsonObject("arguments").get("threadId").getAsLong();
            debugger.stepIn(threadId);
        }
        server.sendResponse(request, null);
    }

    private void handleStepOut(JsonObject request) {
        if (debugger != null) {
            long threadId = request.getAsJsonObject("arguments").get("threadId").getAsLong();
            debugger.stepOut(threadId);
        }
        server.sendResponse(request, null);
    }

    private void handleDisconnect(JsonObject request) {
        if (debugger != null) {
            debugger.disconnect();
        }
        server.sendResponse(request, null);
        System.exit(0);
    }

    private void handleConfigurationDone(JsonObject request) {
        if (debugger != null) {
            debugger.configurationDone();
        }
        server.sendResponse(request, null);
    }
}
