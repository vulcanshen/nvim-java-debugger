package io.vulcanshen.demo;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        System.out.println("Maven Demo App");

        Map<String, Object> data = new HashMap<>();
        data.put("name", "Vulcan");
        data.put("count", 3);

        Gson gson = new Gson();
        String json = gson.toJson(data);
        System.out.println("JSON: " + json);

        for (int i = 0; i < 3; i++) {
            String msg = greet("Vulcan", i);
            System.out.println(msg);
        }

        System.out.println("Done!");
    }

    private static String greet(String name, int index) {
        return "Hello, " + name + "! (#" + index + ")";
    }
}
