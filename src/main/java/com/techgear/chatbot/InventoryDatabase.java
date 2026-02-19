package com.techgear.chatbot;

import java.sql.*;

/**
 * Manages the SQLite inventory database connection and provides
 * parameterised queries for product stock and pricing.
 */
public class InventoryDatabase {

    private static final String SEED_SQL = """
            CREATE TABLE IF NOT EXISTS product_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_name TEXT NOT NULL,
                size TEXT NOT NULL,
                stock_count INTEGER NOT NULL,
                price_gbp DECIMAL(10, 2) NOT NULL
            );
            INSERT OR IGNORE INTO product_inventory (id, item_name, size, stock_count, price_gbp) VALUES
            (1, 'Waterproof Commuter Jacket', 'S',  5, 85.00),
            (2, 'Waterproof Commuter Jacket', 'M',  0, 85.00),
            (3, 'Waterproof Commuter Jacket', 'L', 12, 85.00),
            (4, 'Waterproof Commuter Jacket', 'XL', 3, 85.00),
            (5, 'Tech-Knit Hoodie',           'M', 10, 45.00),
            (6, 'Tech-Knit Hoodie',           'S',  0, 45.00),
            (7, 'Dry-Fit Running Tee',        'L', 20, 25.00),
            (8, 'Dry-Fit Running Tee',        'M', 15, 25.00);
            """;

    private final Connection connection;

    public InventoryDatabase(String dbPath) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initialiseSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to inventory database: " + dbPath, e);
        }
    }

    private void initialiseSchema() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet tables = meta.getTables(null, null, "product_inventory", null)) {
            if (!tables.next()) {
                try (Statement stmt = connection.createStatement()) {
                    for (String sql : SEED_SQL.split(";")) {
                        String trimmed = sql.trim();
                        if (!trimmed.isEmpty()) {
                            stmt.execute(trimmed);
                        }
                    }
                }
            }
        }
    }

    /**
     * Queries the inventory for a given item name and optional size.
     * Returns a human-readable string of matching rows that the LLM
     * can use to formulate its answer.
     */
    public String queryInventory(String itemName, String size) {
        String sql = "SELECT item_name, size, stock_count, price_gbp "
                   + "FROM product_inventory "
                   + "WHERE LOWER(item_name) LIKE LOWER(?)";

        if (size != null && !size.isBlank()) {
            sql += " AND UPPER(size) = UPPER(?)";
        }

        StringBuilder result = new StringBuilder();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, "%" + itemName + "%");
            if (size != null && !size.isBlank()) {
                stmt.setString(2, size.trim());
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.append(String.format(
                            "Item: %s | Size: %s | Stock: %d | Price: £%.2f%n",
                            rs.getString("item_name"),
                            rs.getString("size"),
                            rs.getInt("stock_count"),
                            rs.getDouble("price_gbp")));
                }
            }
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }

        return result.isEmpty()
                ? "No matching products found in the inventory."
                : result.toString().trim();
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // best-effort close
        }
    }
}
