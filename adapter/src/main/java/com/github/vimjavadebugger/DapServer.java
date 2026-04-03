package com.github.vimjavadebugger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DAP Server - 透過 stdin/stdout 與 nvim-dap 溝通。
 *
 * DAP 協議格式：
 * Content-Length: <length>\r\n
 * \r\n
 * <JSON body>
 */
public class DapServer {

    private final BufferedReader reader;
    private final OutputStream writer;
    private final Gson gson;
    private final DapMessageHandler handler;
    private int sequenceCounter = 1;

    public DapServer() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.writer = System.out;
        this.gson = new Gson();
        this.handler = new DapMessageHandler(this);
    }

    public void start() throws IOException {
        while (true) {
            String message = readMessage();
            if (message == null) {
                break;
            }

            JsonObject request = gson.fromJson(message, JsonObject.class);
            handler.handle(request);
        }
    }

    private String readMessage() throws IOException {
        int contentLength = -1;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith("Content-Length: ")) {
                contentLength = Integer.parseInt(line.substring("Content-Length: ".length()).trim());
            }
        }

        if (contentLength <= 0) {
            return null;
        }

        char[] buffer = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(buffer, read, contentLength - read);
            if (n == -1) {
                return null;
            }
            read += n;
        }

        return new String(buffer);
    }

    public void sendErrorResponse(JsonObject request, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("seq", sequenceCounter++);
        response.addProperty("type", "response");
        response.addProperty("request_seq", request.get("seq").getAsInt());
        response.addProperty("success", false);
        response.addProperty("command", request.get("command").getAsString());
        response.addProperty("message", message);
        sendMessage(response);
    }

    public void sendResponse(JsonObject request, JsonObject body) {
        JsonObject response = new JsonObject();
        response.addProperty("seq", sequenceCounter++);
        response.addProperty("type", "response");
        response.addProperty("request_seq", request.get("seq").getAsInt());
        response.addProperty("success", true);
        response.addProperty("command", request.get("command").getAsString());
        if (body != null) {
            response.add("body", body);
        }
        sendMessage(response);
    }

    public void sendEvent(String event, JsonObject body) {
        JsonObject eventObj = new JsonObject();
        eventObj.addProperty("seq", sequenceCounter++);
        eventObj.addProperty("type", "event");
        eventObj.addProperty("event", event);
        if (body != null) {
            eventObj.add("body", body);
        }
        sendMessage(eventObj);
    }

    private void sendMessage(JsonObject message) {
        try {
            String json = gson.toJson(message);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String header = "Content-Length: " + bytes.length + "\r\n\r\n";
            writer.write(header.getBytes(StandardCharsets.UTF_8));
            writer.write(bytes);
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        new DapServer().start();
    }
}
