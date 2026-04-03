package com.github.vimjavadebugger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DapServerTest {

    private final Gson gson = new Gson();

    @Test
    void initializeRequestReturnsCapabilities() throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("seq", 1);
        request.addProperty("type", "request");
        request.addProperty("command", "initialize");
        JsonObject args = new JsonObject();
        args.addProperty("clientID", "neovim");
        request.add("arguments", args);

        String requestJson = gson.toJson(request);
        byte[] requestBytes = requestJson.getBytes(StandardCharsets.UTF_8);
        String dapMessage = "Content-Length: " + requestBytes.length + "\r\n\r\n" + requestJson;

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;

        ByteArrayInputStream testIn = new ByteArrayInputStream(
            dapMessage.getBytes(StandardCharsets.UTF_8)
        );
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();

        System.setIn(testIn);
        System.setOut(new PrintStream(testOut));

        try {
            DapServer server = new DapServer();
            server.start();
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        String output = testOut.toString(StandardCharsets.UTF_8.name());
        String[] parts = output.split("Content-Length: ");

        assertTrue(parts.length >= 2, "Should have at least one DAP message");

        String firstMessage = extractJsonFromDapMessage(parts[1]);
        JsonObject response = gson.fromJson(firstMessage, JsonObject.class);

        assertEquals("response", response.get("type").getAsString());
        assertEquals("initialize", response.get("command").getAsString());
        assertTrue(response.get("success").getAsBoolean());
        assertTrue(response.getAsJsonObject("body")
            .get("supportsConfigurationDoneRequest").getAsBoolean());
    }

    @Test
    void dapMessageFormat() {
        String json = "{\"test\":true}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String expected = "Content-Length: " + bytes.length + "\r\n\r\n" + json;
        String header = "Content-Length: " + bytes.length + "\r\n\r\n";
        String message = header + json;
        assertEquals(expected, message);
    }

    private String extractJsonFromDapMessage(String raw) {
        int jsonStart = raw.indexOf("{");
        if (jsonStart < 0) return "";
        int depth = 0;
        for (int i = jsonStart; i < raw.length(); i++) {
            if (raw.charAt(i) == '{') depth++;
            if (raw.charAt(i) == '}') depth--;
            if (depth == 0) return raw.substring(jsonStart, i + 1);
        }
        return raw.substring(jsonStart);
    }
}
