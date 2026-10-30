package com.example.stmtcachebench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DbController {

  static final String ITEM_BY_ID =
      "select i1_0.id,i1_0.sku,i1_0.category_id,i1_0.name,i1_0.description,i1_0.price,"
          + "i1_0.stock,i1_0.active,i1_0.created_at,i1_0.updated_at from item i1_0 where i1_0.id=?";

  static final String CUSTOMER_BY_EMAIL =
      "select c1_0.id,c1_0.email,c1_0.first_name,c1_0.last_name,c1_0.tier,c1_0.country,"
          + "c1_0.created_at from customer c1_0 where c1_0.email=?";

  static final String RECENT_ORDERS =
      "select o1_0.id,o1_0.customer_id,o1_0.quantity,o1_0.unit_price,o1_0.status,o1_0.created_at,"
          + "i1_0.id,i1_0.sku,i1_0.name,i1_0.price from orders o1_0 join item i1_0"
          + " on i1_0.id=o1_0.item_id where o1_0.customer_id=? order by o1_0.created_at desc"
          + " limit ?";

  static final String ORDER_COUNT_BY_STATUS =
      "select o1_0.status,count(o1_0.id) from orders o1_0 where o1_0.customer_id=?"
          + " and o1_0.status in (?,?,?) group by o1_0.status";

  static final String ITEMS_IN_CATEGORY =
      "select i1_0.id,i1_0.sku,i1_0.name,i1_0.price,i1_0.stock from item i1_0"
          + " where i1_0.category_id=? and i1_0.price between ? and ? and i1_0.active=?"
          + " order by i1_0.price limit ?";

  private final DataSource dataSource;

  public DbController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @GetMapping("/nodb")
  public String noDb() {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    long acc = 0;
    for (int i = 0; i < 5; i++) {
      acc += r.nextInt(Seeder.ITEMS);
    }
    return "ok " + acc;
  }

  @GetMapping("/db")
  public String db() throws SQLException {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int customer = 1 + r.nextInt(Seeder.CUSTOMERS);
    long acc = 0;
    try (Connection c = dataSource.getConnection()) {
      try (PreparedStatement ps = c.prepareStatement(ITEM_BY_ID)) {
        ps.setInt(1, 1 + r.nextInt(Seeder.ITEMS));
        acc += drain(ps);
      }
      try (PreparedStatement ps = c.prepareStatement(CUSTOMER_BY_EMAIL)) {
        ps.setString(1, "user" + customer + "@example.com");
        acc += drain(ps);
      }
      try (PreparedStatement ps = c.prepareStatement(RECENT_ORDERS)) {
        ps.setInt(1, customer);
        ps.setInt(2, 10);
        acc += drain(ps);
      }
      try (PreparedStatement ps = c.prepareStatement(ORDER_COUNT_BY_STATUS)) {
        ps.setInt(1, customer);
        ps.setString(2, "NEW");
        ps.setString(3, "PAID");
        ps.setString(4, "SHIPPED");
        acc += drain(ps);
      }
      try (PreparedStatement ps = c.prepareStatement(ITEMS_IN_CATEGORY)) {
        ps.setInt(1, 1 + r.nextInt(Seeder.CATEGORIES));
        int low = r.nextInt(900);
        ps.setBigDecimal(2, java.math.BigDecimal.valueOf(low));
        ps.setBigDecimal(3, java.math.BigDecimal.valueOf(low + 50));
        ps.setBoolean(4, true);
        ps.setInt(5, 20);
        acc += drain(ps);
      }
    }
    return "ok " + acc;
  }

  private static long drain(PreparedStatement ps) throws SQLException {
    long rows = 0;
    try (ResultSet rs = ps.executeQuery()) {
      int cols = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        for (int i = 1; i <= cols; i++) {
          rs.getObject(i);
        }
        rows++;
      }
    }
    return rows;
  }
}
