package com.techgear.chatbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the company knowledge base from a text file and provides its contents
 * to the LLM when a knowledge-base tool call is triggered.
 */
public class KnowledgeBase {

    private final String content;

    public KnowledgeBase(String filePath) {
        try {
            this.content = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load knowledge base from: " + filePath, e);
        }
    }

    /**
     * Returns the full knowledge base content.
     * The KB is small enough to return in its entirety, letting the LLM
     * extract the relevant answer from the text.
     */
    public String search(String query) {
        return content;
    }
}
