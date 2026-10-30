package com.example.stmtcachebench;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Random;

public final class Seeder {
  static final int ITEMS = 10_000;
  static final int CUSTOMERS = 2_000;
  static final int ORDERS = 40_000;
  static final int CATEGORIES = 50;

  public static void main(String[] args) throws Exception {
    String url =
        "jdbc:mysql://"
            + System.getProperty("MYSQL_HOST", System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1"))
            + ":"
            + System.getProperty("MYSQL_PORT", System.getenv().getOrDefault("MYSQL_PORT", "13306"))
            + "/bench?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
    Random r = new Random(42);
    try (Connection c = DriverManager.getConnection(url, "bench", "bench");
        Statement s = c.createStatement()) {
      s.execute("DROP TABLE IF EXISTS orders");
      s.execute("DROP TABLE IF EXISTS customer");
      s.execute("DROP TABLE IF EXISTS item");
      s.execute(
          "CREATE TABLE item (id INT PRIMARY KEY, sku VARCHAR(32) NOT NULL UNIQUE,"
              + " category_id INT NOT NULL, name VARCHAR(100) NOT NULL, description VARCHAR(255),"
              + " price DECIMAL(10,2) NOT NULL, stock INT NOT NULL, active BOOLEAN NOT NULL,"
              + " created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,"
              + " KEY idx_item_cat_price (category_id, price))");
      s.execute(
          "CREATE TABLE customer (id INT PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE,"
              + " first_name VARCHAR(50) NOT NULL, last_name VARCHAR(50) NOT NULL,"
              + " tier VARCHAR(10) NOT NULL, country CHAR(2) NOT NULL, created_at DATETIME NOT NULL)");
      s.execute(
          "CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL, item_id INT NOT NULL,"
              + " quantity INT NOT NULL, unit_price DECIMAL(10,2) NOT NULL,"
              + " status VARCHAR(16) NOT NULL, created_at DATETIME NOT NULL,"
              + " KEY idx_orders_customer_created (customer_id, created_at),"
              + " KEY idx_orders_customer_status (customer_id, status))");
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO item VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW() - INTERVAL ? MINUTE, NOW())")) {
        for (int i = 1; i <= ITEMS; i++) {
          ps.setInt(1, i);
          ps.setString(2, "SKU-" + i);
          ps.setInt(3, 1 + r.nextInt(CATEGORIES));
          ps.setString(4, "Item number " + i);
          ps.setString(5, "A fairly ordinary description for item " + i);
          ps.setBigDecimal(6, java.math.BigDecimal.valueOf(100 + r.nextInt(100_000), 2));
          ps.setInt(7, r.nextInt(500));
          ps.setBoolean(8, r.nextInt(10) != 0);
          ps.setInt(9, r.nextInt(500_000));
          ps.addBatch();
          if (i % 1000 == 0) {
            ps.executeBatch();
          }
        }
      }
      String[] tiers = {"free", "silver", "gold"};
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO customer VALUES (?, ?, ?, ?, ?, ?, NOW() - INTERVAL ? MINUTE)")) {
        for (int i = 1; i <= CUSTOMERS; i++) {
          ps.setInt(1, i);
          ps.setString(2, "user" + i + "@example.com");
          ps.setString(3, "First" + i);
          ps.setString(4, "Last" + i);
          ps.setString(5, tiers[r.nextInt(tiers.length)]);
          ps.setString(6, r.nextBoolean() ? "US" : "FR");
          ps.setInt(7, r.nextInt(500_000));
          ps.addBatch();
        }
        ps.executeBatch();
      }
      String[] statuses = {"NEW", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"};
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO orders VALUES (?, ?, ?, ?, ?, ?, NOW() - INTERVAL ? MINUTE)")) {
        for (int i = 1; i <= ORDERS; i++) {
          ps.setInt(1, i);
          ps.setInt(2, 1 + r.nextInt(CUSTOMERS));
          ps.setInt(3, 1 + r.nextInt(ITEMS));
          ps.setInt(4, 1 + r.nextInt(5));
          ps.setBigDecimal(5, java.math.BigDecimal.valueOf(100 + r.nextInt(100_000), 2));
          ps.setString(6, statuses[r.nextInt(statuses.length)]);
          ps.setInt(7, r.nextInt(500_000));
          ps.addBatch();
          if (i % 1000 == 0) {
            ps.executeBatch();
          }
        }
      }
      c.commit();
      s.execute("ANALYZE TABLE item, customer, orders");
    }
    System.out.println("seeded " + ITEMS + " items, " + CUSTOMERS + " customers, " + ORDERS + " orders");
  }
}
