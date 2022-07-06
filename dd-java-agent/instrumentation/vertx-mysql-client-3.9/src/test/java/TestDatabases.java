import datadog.trace.agent.test.utils.PortUtils;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.MySQLContainer;

public class TestDatabases implements Closeable {

  public static TestDatabases initialise(String dbName) {
    return new TestDatabases(dbName);
  }

  private final MySQLContainer mysql;

  private final Map<String, TestDBInfo> dbInfos;

  private TestDatabases(String dbName) {
    Map<String, TestDBInfo> infos = new HashMap<>();
    mysql =
        new MySQLContainer("mysql:8.0")
            .withDatabaseName(dbName)
            .withUsername("sa")
            .withPassword("sa");
    mysql.start();
    TestDBInfo info =
        new TestDBInfo(
            mysql.getUsername(),
            mysql.getPassword(),
            mysql.getHost(),
            mysql.getMappedPort(MySQLContainer.MYSQL_PORT),
            "mysql",
            dbName);
    PortUtils.waitForPortToOpen(info.host, info.port, 5, TimeUnit.SECONDS);
    infos.put("mysql", info);
    dbInfos = Collections.unmodifiableMap(infos);
  }

  @Override
  public void close() throws IOException {
    if (null != mysql) {
      mysql.close();
    }
  }

  public Map<String, TestDBInfo> getDBInfos() {
    return dbInfos;
  }

  public static class TestDBInfo {
    private final String user;
    private final String password;
    private final String host;
    private final Integer port;
    private final String type;
    private final String dbName;
    private final String uri;

    public TestDBInfo(
        String user, String password, String host, Integer port, String type, String dbName) {
      this.user = user;
      this.password = password;
      this.host = host;
      this.port = port;
      this.type = type;
      this.dbName = dbName;
      this.uri = type + "://" + user + ":" + password + "@" + host + ":" + port + "/" + dbName;
    }

    public String getUser() {
      return user;
    }

    public String getPassword() {
      return password;
    }

    public String getHost() {
      return host;
    }

    public String getPort() {
      return port.toString();
    }

    public String getType() {
      return type;
    }

    public String getDbName() {
      return dbName;
    }

    public String getUri() {
      return uri;
    }
  }
}
