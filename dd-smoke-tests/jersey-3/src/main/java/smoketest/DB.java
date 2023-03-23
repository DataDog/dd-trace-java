package smoketest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DB {

  public static void main(String[] args) throws SQLException {
    DB.store("pepe");
  }

  @SuppressFBWarnings
  public static void store(String value) throws SQLException {
    try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:test_mem");
        Statement st = conn.createStatement()) {
      st.execute("create table pepe (title VARCHAR(50) NOT NULL) ");
      st.executeUpdate(
          new StringBuilder("insert into pepe values('").append(value).append("')").toString());
      System.out.println("Inserted value " + value);
      try (ResultSet rs = st.executeQuery("select * from pepe")) {
        rs.next();
        if (!rs.getString(1).equals(value)) {
          throw new SQLException("Value " + value + " not found in db");
        }
      }
    }
  }
}
