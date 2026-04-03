package com.github.vimjavadebugger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DapMessageHandlerTest {

    private final Gson gson = new Gson();
    private DapServer server;
    private ByteArrayOutputStream testOut;

    @BeforeEach
    void setUp() {
        testOut = new ByteArrayOutputStream();
        InputStream emptyIn = new ByteArrayInputStream(new byte[0]);
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;

        System.setIn(emptyIn);
        System.setOut(new PrintStream(testOut));
        server = new DapServer();
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    @Test
    void handleInitializeReturnsCapabilities() {
        DapMessageHandler handler = new DapMessageHandler(server);

        JsonObject request = new JsonObject();
        request.addProperty("seq", 1);
        request.addProperty("type", "request");
        request.addProperty("command", "initialize");
        request.add("arguments", new JsonObject());

        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(testOut));
        handler.handle(request);
        System.setOut(originalOut);

        String output = testOut.toString();
        assertTrue(output.contains("\"command\":\"initialize\""));
        assertTrue(output.contains("\"success\":true"));
        assertTrue(output.contains("\"event\":\"initialized\""));
    }

    @Test
    void handleUnknownCommandReturnsEmptyResponse() {
        DapMessageHandler handler = new DapMessageHandler(server);

        JsonObject request = new JsonObject();
        request.addProperty("seq", 1);
        request.addProperty("type", "request");
        request.addProperty("command", "unknownCommand");

        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(testOut));
        handler.handle(request);
        System.setOut(originalOut);

        String output = testOut.toString();
        assertTrue(output.contains("\"command\":\"unknownCommand\""));
        assertTrue(output.contains("\"success\":true"));
    }

    @Test
    void handleThreadsWithoutDebuggerReturnsEmptyList() {
        DapMessageHandler handler = new DapMessageHandler(server);

        JsonObject request = new JsonObject();
        request.addProperty("seq", 1);
        request.addProperty("type", "request");
        request.addProperty("command", "threads");

        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(testOut));
        handler.handle(request);
        System.setOut(originalOut);

        String output = testOut.toString();
        assertTrue(output.contains("\"threads\":[]"));
    }
}
