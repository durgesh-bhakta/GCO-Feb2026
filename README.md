# TechGear UK — Tri-Tier Chatbot (CLI)

A Java console chatbot that answers customer queries using three tiers:

| Tier | Source | Example |
|------|--------|---------|
| **Knowledge Base** | `knowledge_base.txt` | Office address, opening hours, delivery costs |
| **Database** | `inventory.db` (SQLite) | Stock levels, product prices |
| **Fallback** | Static message | Anything outside the above two domains |

Routing is handled by an OpenAI LLM with **function/tool calling** — the model decides which
data source to query and extracts the required parameters automatically.

## Prerequisites

- **Java 17+**
- **Apache Maven 3.8+**
- An **Azure OpenAI API key** with access to the `gpt-4o-mini` deployment

## Quick Start

```bash
# 1. Set your API key
set AZURE_OPENAI_KEY=<your-key>

# 2. Build the fat JAR
mvn clean package -q

# 3. Run the chatbot
java -jar target/tri-tier-chatbot-1.0.0.jar
```

## Database Setup

The application ships with `inventory.db`. If the file is missing or the table
does not exist, the app will automatically create and seed it on first run.

To regenerate manually:

```bash
sqlite3 inventory.db < inventory_setup.sql
```

## Architecture

```
User Input
    │
    ▼
LlmRouter  ──▶  Azure OpenAI (gpt-4o-mini) + tool definitions
    │                    │
    │         ┌──────────┴──────────┐
    │         ▼                     ▼
    │   search_knowledge_base  query_inventory
    │         │                     │
    │         ▼                     ▼
    │   KnowledgeBase         InventoryDatabase
    │   (text file)           (SQLite)
    │         │                     │
    │         └──────────┬──────────┘
    │                    ▼
    │         Tool result → LLM → final answer
    │
    ▼
Console Output
```

- **ChatbotApp** — REPL loop and entry point
- **KnowledgeBase** — Loads `knowledge_base.txt` and returns its content on tool call
- **InventoryDatabase** — Connects to SQLite via JDBC; parameterised queries for stock/price
- **LlmRouter** — Builds Azure OpenAI requests, dispatches tool calls, returns the final answer
