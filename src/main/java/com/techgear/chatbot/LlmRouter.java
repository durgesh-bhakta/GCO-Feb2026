package com.techgear.chatbot;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/**
 * Routes user queries through the Azure OpenAI Chat Completions API with
 * function/tool calling to determine the correct data source (KB, DB, or Fallback).
 */
public class LlmRouter {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final String FALLBACK_MESSAGE =
            "I'm sorry, I cannot answer your query at the moment.";

    private static final String SYSTEM_PROMPT = """
            You are a customer service chatbot for TechGear UK. Follow these rules strictly:

            1. For questions about the company (address, location, opening hours, delivery,
               returns, contact details), call the **search_knowledge_base** tool.
            2. For questions about products, stock availability, stock counts, or prices,
               call the **query_inventory** tool.
            3. For ANY question that cannot be answered by either tool, respond EXACTLY with:
               "I'm sorry, I cannot answer your query at the moment."
            4. All monetary values must be in GBP (£), formatted to two decimal places.
            5. Use UK English spelling and conventions throughout.
            6. Be concise — directly answer what was asked without unnecessary preamble.
            7. Never invent information. Only use data returned by the tools.
            """;

    private static final String TOOLS_JSON = """
            [
              {
                "type": "function",
                "function": {
                  "name": "search_knowledge_base",
                  "description": "Search the TechGear UK company knowledge base. Contains: office address and location, opening hours (weekdays and Saturday), delivery policy and costs, returns policy, and contact details (email and phone).",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "query": {
                        "type": "string",
                        "description": "The user's question about company information"
                      }
                    },
                    "required": ["query"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "query_inventory",
                  "description": "Query the product inventory database for stock availability, stock count, or price. Available products: Waterproof Commuter Jacket, Tech-Knit Hoodie, Dry-Fit Running Tee. Available sizes: S, M, L, XL.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "item_name": {
                        "type": "string",
                        "description": "The product name to look up, e.g. 'Waterproof Commuter Jacket'"
                      },
                      "size": {
                        "type": "string",
                        "description": "Optional size filter (S, M, L, or XL). Omit when asking about price only.",
                        "enum": ["S", "M", "L", "XL"]
                      }
                    },
                    "required": ["item_name"]
                  }
                }
              }
            ]
            """;

    private final String apiUrl;
    private final String apiKey;
    private final KnowledgeBase knowledgeBase;
    private final InventoryDatabase inventoryDb;
    private final HttpClient httpClient;
    private final Gson gson;
    private final JsonArray tools;

    public LlmRouter(String endpoint, String apiKey, String apiVersion, String model,
                     KnowledgeBase knowledgeBase, InventoryDatabase inventoryDb) {
        String base = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        this.apiUrl = base + "openai/deployments/" + model
                + "/chat/completions?api-version=" + apiVersion;
        this.apiKey = apiKey;
        this.knowledgeBase = knowledgeBase;
        this.inventoryDb = inventoryDb;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.gson = new Gson();
        this.tools = JsonParser.parseString(TOOLS_JSON).getAsJsonArray();
    }

    /**
     * Routes a user query: calls the LLM, handles any tool calls, and returns the final response.
     */
    public String route(String userQuery) {
        try {
            JsonArray messages = new JsonArray();
            messages.add(createMessage("system", SYSTEM_PROMPT));
            messages.add(createMessage("user", userQuery));

            JsonObject assistantMsg = chat(messages, true);

            if (hasToolCalls(assistantMsg)) {
                messages.add(assistantMsg);
                processToolCalls(assistantMsg, messages);
                assistantMsg = chat(messages, false);
            }

            return extractContent(assistantMsg);

        } catch (Exception e) {
            System.err.println("[LlmRouter] Error: " + e.getMessage());
            return "An error occurred whilst processing your request. Please try again.";
        }
    }

    // -- Azure OpenAI API interaction ----------------------------------------

    private JsonObject chat(JsonArray messages, boolean includeTools) {
        JsonObject body = new JsonObject();
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);

        if (includeTools) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Azure OpenAI API request failed: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Azure OpenAI API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonArray("choices")
                   .get(0).getAsJsonObject()
                   .getAsJsonObject("message");
    }

    // -- Tool execution ------------------------------------------------------

    private boolean hasToolCalls(JsonObject message) {
        return message.has("tool_calls")
                && message.getAsJsonArray("tool_calls").size() > 0;
    }

    private void processToolCalls(JsonObject assistantMsg, JsonArray messages) {
        for (JsonElement element : assistantMsg.getAsJsonArray("tool_calls")) {
            JsonObject toolCall = element.getAsJsonObject();
            String id = toolCall.get("id").getAsString();
            JsonObject function = toolCall.getAsJsonObject("function");
            String name = function.get("name").getAsString();
            JsonObject args = JsonParser.parseString(
                    function.get("arguments").getAsString()).getAsJsonObject();

            String result = executeTool(name, args);

            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", id);
            toolMsg.addProperty("content", result);
            messages.add(toolMsg);
        }
    }

    private String executeTool(String name, JsonObject args) {
        return switch (name) {
            case "search_knowledge_base" -> {
                String query = args.get("query").getAsString();
                yield knowledgeBase.search(query);
            }
            case "query_inventory" -> {
                String itemName = args.get("item_name").getAsString();
                String size = args.has("size") && !args.get("size").isJsonNull()
                        ? args.get("size").getAsString()
                        : null;
                yield inventoryDb.queryInventory(itemName, size);
            }
            default -> "Unknown tool: " + name;
        };
    }

    // -- Helpers -------------------------------------------------------------

    private JsonObject createMessage(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    private String extractContent(JsonObject message) {
        if (message.has("content") && !message.get("content").isJsonNull()) {
            return message.get("content").getAsString();
        }
        return FALLBACK_MESSAGE;
    }
}
