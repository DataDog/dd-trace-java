import com.mchange.v2.c3p0.ComboPooledDataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Unroll

import javax.sql.DataSource
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

// workaround for SSLHandShakeException on J9 only with Hikari/MySQL
@Requires({ jvm.java8Compatible && !System.getProperty("java.vendor").contains("IBM") })
class RemoteJDBCInstrumentationTest extends AgentTestRunner {
  @Shared
  def dbName = "jdbcUnitTest"

  @Shared
  private Map<String, String> jdbcUrls = [
    "postgresql": "jdbc:postgresql://localhost:5432/$dbName",
    "mysql"     : "jdbc:mysql://localhost:3306/$dbName"
  ]

  @Shared
  private Map<String, String> jdbcDriverClassNames = [
    "postgresql": "org.postgresql.Driver",
    "mysql"     : "com.mysql.jdbc.Driver"
  ]

  @Shared
  private Map<String, String> jdbcUserNames = [
    "postgresql": "sa",
    "mysql"     : "sa"
  ]

  @Shared
  private Map<String, String> jdbcPasswords = [
    "mysql"     : "sa",
    "postgresql": "sa"
  ]

  @Shared
  def postgres
  @Shared
  def mysql

  @Shared
  private Properties peerConnectionProps = {
    def props = new Properties()
    props.setProperty("user", "sa")
    props.setProperty("password", "sa")
    return props
  }()

  // JDBC Connection pool name (i.e. HikariCP) -> Map<dbName, Datasource>
  @Shared
  private Map<String, Map<String, DataSource>> cpDatasources = new HashMap<>()

  def prepareConnectionPoolDatasources() {
    String[] connectionPoolNames = ["tomcat", "hikari", "c3p0",]
    connectionPoolNames.each { cpName ->
      Map<String, DataSource> dbDSMapping = new HashMap<>()
      jdbcUrls.each { dbType, jdbcUrl ->
        dbDSMapping.put(dbType, createDS(cpName, dbType, jdbcUrl))
      }
      cpDatasources.put(cpName, dbDSMapping)
    }
  }

  def createTomcatDS(String dbType, String jdbcUrl) {
    DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource()
    ds.setUrl(jdbcUrl)
    ds.setDriverClassName(jdbcDriverClassNames.get(dbType))
    String username = jdbcUserNames.get(dbType)
    if (username != null) {
      ds.setUsername(username)
    }
    ds.setPassword(jdbcPasswords.get(dbType))
    ds.setMaxActive(1) // to test proper caching, having > 1 max active connection will be hard to
    // determine whether the connection is properly cached
    return ds
  }

  def createHikariDS(String dbType, String jdbcUrl) {
    HikariConfig config = new HikariConfig()
    config.setJdbcUrl(jdbcUrl)
    String username = jdbcUserNames.get(dbType)
    if (username != null) {
      config.setUsername(username)
    }
    config.setPassword(jdbcPasswords.get(dbType))
    config.addDataSourceProperty("cachePrepStmts", "true")
    config.addDataSourceProperty("prepStmtCacheSize", "250")
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    config.setMaximumPoolSize(1)

    return new HikariDataSource(config)
  }

  def createC3P0DS(String dbType, String jdbcUrl) {
    DataSource ds = new ComboPooledDataSource()
    ds.setDriverClass(jdbcDriverClassNames.get(dbType))
    def jdbcUrlToSet = dbType == "derby" ? jdbcUrl + ";create=true" : jdbcUrl
    ds.setJdbcUrl(jdbcUrlToSet)
    String username = jdbcUserNames.get(dbType)
    if (username != null) {
      ds.setUser(username)
    }
    ds.setPassword(jdbcPasswords.get(dbType))
    ds.setMaxPoolSize(1)
    return ds
  }

  def createDS(String connectionPoolName, String dbType, String jdbcUrl) {
    DataSource ds = null
    if (connectionPoolName == "tomcat") {
      ds = createTomcatDS(dbType, jdbcUrl)
    }
    if (connectionPoolName == "hikari") {
      ds = createHikariDS(dbType, jdbcUrl)
    }
    if (connectionPoolName == "c3p0") {
      ds = createC3P0DS(dbType, jdbcUrl)
    }
    return ds
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.jdbc.prepared.statement.class.name", "test.TestPreparedStatement")
    injectSysConfig("dd.integration.jdbc-datasource.enabled", "true")
  }

  def setupSpec() {
    postgres = new PostgreSQLContainer("postgres:11.1")
      .withDatabaseName(dbName).withUsername("sa").withPassword("sa")
    postgres.start()
    PortUtils.waitForPortToOpen(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put("postgresql", "${postgres.getJdbcUrl()}")
    mysql = new MySQLContainer("mysql:8.0")
      .withDatabaseName(dbName).withUsername("sa").withPassword("sa")
    mysql.start()
    PortUtils.waitForPortToOpen(mysql.getHost(), mysql.getMappedPort(MySQLContainer.MYSQL_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put("mysql", "${mysql.getJdbcUrl()}")

    prepareConnectionPoolDatasources()
  }

  def cleanupSpec() {
    cpDatasources.values().each {
      it.values().each { datasource ->
        if (datasource instanceof Closeable) {
          datasource.close()
        }
      }
    }
    postgres?.close()
    mysql?.close()
  }

  @Unroll
  def "basic statement with #connection.getClass().getCanonicalName() on #driver generates spans"() {
    setup:
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

    when:
    Statement statement = connection.createStatement()
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery(query)
    }
    TEST_WRITER.waitForTraces(1)

    then:
    resultSet.next()
    resultSet.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName renameService ? dbName.toLowerCase() : driver
          operationName "${driver}.query"
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            "$Tags.PEER_HOSTNAME" String
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * _

    cleanup:
    statement.close()
    connection.close()

    where:
    driver       | connection                                              | renameService | query                   | operation | obfuscatedQuery
    "mysql"      | connectTo(driver, peerConnectionProps)                  | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | connectTo(driver, peerConnectionProps)                  | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user"
    "mysql"      | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user"
    "mysql"      | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user"
    "mysql"      | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user"
  }

  @Unroll
  def "prepared statement execute on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    PreparedStatement statement = connection.prepareStatement(query)

    when:
    ResultSet resultSet = runUnderTrace("parent") {
      assert statement.execute()
      return statement.resultSet
    }
    TEST_WRITER.waitForTraces(1)

    then:
    resultSet.next()
    resultSet.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "${driver}.query"
          serviceName driver
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // only set when there is an out of proc instance (postgresql, mysql)
            "$Tags.PEER_HOSTNAME" String
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * _

    cleanup:
    statement.close()
    connection.close()

    where:
    driver       | connection                                              | query                   | operation | obfuscatedQuery
    "mysql"      | connectTo(driver, peerConnectionProps)                  | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | connectTo(driver, peerConnectionProps)                  | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
  }

  @Unroll
  def "prepared statement query on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    PreparedStatement statement = connection.prepareStatement(query)
    when:
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    resultSet.next()
    resultSet.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "${driver}.query"
          serviceName driver
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // only set when there is an out of proc instance (postgresql, mysql)
            "$Tags.PEER_HOSTNAME" String
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * _

    cleanup:
    statement.close()
    connection.close()

    where:
    driver       | connection                                              | query                   | operation | obfuscatedQuery
    "mysql"      | connectTo(driver, peerConnectionProps)                  | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | connectTo(driver, peerConnectionProps)                  | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
  }

  @Unroll
  def "prepared call on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    CallableStatement statement = connection.prepareCall(query)
    when:
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    resultSet.next()
    resultSet.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "${driver}.query"
          serviceName driver
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // only set when there is an out of proc instance (postgresql, mysql)
            "$Tags.PEER_HOSTNAME" String
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
            "${Tags.DB_OPERATION}" operation
            defaultTags()
          }
        }
      }
    }
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * _

    cleanup:
    statement.close()
    connection.close()

    where:
    driver       | connection                                              | query                   | operation | obfuscatedQuery
    "mysql"      | connectTo(driver, peerConnectionProps)                  | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | connectTo(driver, peerConnectionProps)                  | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    "mysql"      | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    "postgresql" | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
  }

  @Unroll
  def "statement update on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    Statement statement = connection.createStatement()
    def sql = connection.nativeSQL(query)

    when:
    runUnderTrace("parent") {
      return !statement.execute(sql)
    }
    TEST_WRITER.waitForTraces(1)

    then:
    statement.updateCount == 0
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "${driver}.query"
          serviceName driver
          resourceName query
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "java-jdbc-statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // only set when there is an out of proc instance (postgresql, mysql)
            "$Tags.PEER_HOSTNAME" String
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
            "${Tags.DB_OPERATION}" operation
            defaultTags()
          }
        }
      }
    }
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * _

    cleanup:
    statement.close()
    connection.close()

    where:
    driver       | connection                                              | query                                                                            | operation
    "mysql"      | connectTo(driver, peerConnectionProps)                  | "CREATE TEMPORARY TABLE s_test_ (id INTEGER not NULL, PRIMARY KEY ( id ))"       | "CREATE"
    "postgresql" | connectTo(driver, peerConnectionProps)                  | "CREATE TEMPORARY TABLE s_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    "mysql"      | cpDatasources.get("tomcat").get(driver).getConnection() | "CREATE TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "postgresql" | cpDatasources.get("tomcat").get(driver).getConnection() | "CREATE TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "mysql"      | cpDatasources.get("hikari").get(driver).getConnection() | "CREATE TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "postgresql" | cpDatasources.get("hikari").get(driver).getConnection() | "CREATE TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "mysql"      | cpDatasources.get("c3p0").get(driver).getConnection()   | "CREATE TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
    "postgresql" | cpDatasources.get("c3p0").get(driver).getConnection()   | "CREATE TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }

  Driver driverFor(String db) {
    return newDriver(jdbcDriverClassNames.get(db))
  }

  Connection connectTo(String db, Properties properties) {
    return connect(jdbcDriverClassNames.get(db), jdbcUrls.get(db), properties)
  }

  Driver newDriver(String driverClass) {
    return ((Driver) Class.forName(driverClass)
      .getDeclaredConstructor().newInstance())
  }

  Connection connect(String driverClass, String url, Properties properties) {
    return newDriver(driverClass)
      .connect(url, properties)
  }
}
