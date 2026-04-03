public class HelloWorld {
    public static void main(String[] args) {
        String name = "Vulcan";
        int count = 5;

        for (int i = 0; i < count; i++) {
            String message = greeting(name, i);
            System.out.println(message);
        }

        System.out.println("Done!");
    }

    private static String greeting(String name, int index) {
        String result = "Hello, " + name + "! (#" + index + ")";
        return result;
    }
}
