package com.techgear.chatbot;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Entry point for the TechGear UK Tri-Tier Chatbot.
 * Runs a continuous REPL loop, routing each query through the LLM router.
 */
public class ChatbotApp {

    private static final String BANNER = """
            +----------------------------------------------+
            |     TechGear UK - Customer Support Chat      |
            +----------------------------------------------+
            Type your question below. Type 'exit' to quit.
            """;

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        System.out.println(BANNER);

        String endpoint = envOrDefault("AZURE_OPENAI_ENDPOINT",
                "https://greatcodeoff.openai.azure.com/");
        String apiKey = System.getenv("AZURE_OPENAI_KEY");
        String apiVersion = envOrDefault("AZURE_API_VERSION", "2025-01-01-preview");
        String model = envOrDefault("AZURE_OPENAI_MODEL", "gpt-4o-mini");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: AZURE_OPENAI_KEY environment variable is not set.");
            System.err.println("  set AZURE_OPENAI_KEY=<your-key>");
            System.exit(1);
        }

        KnowledgeBase knowledgeBase = new KnowledgeBase("knowledge_base.txt");
        InventoryDatabase inventoryDb = new InventoryDatabase("./inventory.db");
        LlmRouter router = new LlmRouter(endpoint, apiKey, apiVersion, model,
                knowledgeBase, inventoryDb);

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("You: ");
                if (!scanner.hasNextLine()) break;

                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    System.out.println("Bot: Thank you for chatting with TechGear UK. Goodbye!");
                    break;
                }
                if (input.isEmpty()) continue;

                String response = router.route(input);
                System.out.println("Bot: " + response);
                System.out.println();
            }
        } finally {
            inventoryDb.close();
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
