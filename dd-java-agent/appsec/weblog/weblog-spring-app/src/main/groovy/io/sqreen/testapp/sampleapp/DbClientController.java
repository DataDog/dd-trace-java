package io.sqreen.testapp.sampleapp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;

@RestController
@RequestMapping("/db")
public class DbClientController {

  private static final String DEFAULT_USER = "user";
  private static final String DEFAULT_PASS = "dummy";

  @RequestMapping(value = "/mysql")
  public void mysqlConnect() throws SQLException {
    Connection con =
        DriverManager.getConnection("jdbc:mysql://localhost:3306/test", DEFAULT_USER, DEFAULT_PASS);
    con.close();
  }

  @RequestMapping(value = "/postgres")
  public void postgresConnect() throws SQLException {
    Connection con =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/test", DEFAULT_USER, DEFAULT_PASS);
    con.close();
  }

  @RequestMapping(value = "/mongo")
  public void mongoConnect() throws SQLException {
    Connection con =
        DriverManager.getConnection(
            "jdbc:mongodb://localhost:27017/test", DEFAULT_USER, DEFAULT_PASS);
    con.close();
  }

  @RequestMapping(value = "/redis")
  public void redisConnect() {
    Jedis jedis = new Jedis("localhost", 6379);
    jedis.aclUsers();
    jedis.close();
  }
}
