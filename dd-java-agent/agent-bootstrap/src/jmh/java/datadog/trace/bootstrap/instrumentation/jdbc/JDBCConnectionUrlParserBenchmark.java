package datadog.trace.bootstrap.instrumentation.jdbc;

import java.util.Properties;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class JDBCConnectionUrlParserBenchmark {

  @Param({
    "jdbc:postgresql://pg.host:11/pgdb?user=pguser&password=PW",
    "jdbc:mysql://my.host:22/mydb?user=myuser&password=PW",
    "jdbc:mariadb:failover://mdb.host1:33,mdb.host/mdbdb?characterEncoding=utf8",
    "jdbc:mysql:replication://address=(HOST=mdb.host),"
        + "address=(host=anotherhost)(port=3306)(user=wrong)(password=PW)/mdbdb?user=mdbuser&password=PW"
  })
  private String connectionString;

  private Properties properties;

  @Setup(Level.Trial)
  public void setup() {
    properties = new Properties();
    // https://download.oracle.com/otn-pub/jcp/jdbc-4_1-mrel-spec/jdbc4.1-fr-spec.pdf
    properties.setProperty("databaseName", "stdDatabaseName");
    properties.setProperty("dataSourceName", "stdDatasourceName");
    properties.setProperty("description", "Some description");
    properties.setProperty("networkProtocol", "stdProto");
    properties.setProperty("password", "PASSWORD!");
    properties.setProperty("portNumber", "9999");
    properties.setProperty("roleName", "stdRoleName");
    properties.setProperty("serverName", "stdServerName");
    properties.setProperty("user", "stdUserName");
  }

  @Benchmark
  public DBInfo parse() {
    return JDBCConnectionUrlParser.parse(connectionString, properties);
  }
}
