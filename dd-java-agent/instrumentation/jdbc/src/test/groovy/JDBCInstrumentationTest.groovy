import com.mchange.v2.c3p0.ComboPooledDataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.derby.jdbc.EmbeddedDataSource
import org.h2.jdbcx.JdbcDataSource
import spock.lang.Shared
import spock.lang.Unroll
import test.TestConnection

import javax.sql.DataSource
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

class JDBCInstrumentationTest extends AgentTestRunner {
  @Shared
  def dbName = "jdbcUnitTest"

  @Shared
  private Map<String, String> jdbcUrls = [
    "h2"    : "jdbc:h2:mem:$dbName",
    "derby" : "jdbc:derby:memory:$dbName",
    "hsqldb": "jdbc:hsqldb:mem:$dbName",
  ]

  @Shared
  private Map<String, String> jdbcDriverClassNames = [
    "h2"    : "org.h2.Driver",
    "derby" : "org.apache.derby.jdbc.EmbeddedDriver",
    "hsqldb": "org.hsqldb.jdbc.JDBCDriver",
  ]

  @Shared
  private Map<String, String> jdbcUserNames = [
    "h2"    : null,
    "derby" : "APP",
    "hsqldb": "SA",
  ]

  @Shared
  private Map<String, String> jdbcPasswords = [
    "h2"    : "",
    "derby" : "",
    "hsqldb": ""
  ]

  @Shared
  private Properties connectionProps = {
    def props = new Properties()
//    props.put("user", "someUser")
//    props.put("password", "somePassword")
    props.put("databaseName", "someDb")
    props.put("OPEN_NEW", "true") // So H2 doesn't complain about username/password.
    return props
  }()

  // JDBC Connection pool name (i.e. HikariCP) -> Map<dbName, Datasource>
  @Shared
  private Map<String, Map<String, DataSource>> cpDatasources = new HashMap<>()

  def prepareConnectionPoolDatasources() {
    String[] connectionPoolNames = [
      "tomcat", "hikari", "c3p0",
    ]
    connectionPoolNames.each {
      cpName ->
        Map<String, DataSource> dbDSMapping = new HashMap<>()
        jdbcUrls.each {
          dbType, jdbcUrl ->
            dbDSMapping.put(dbType, createDS(cpName, dbType, jdbcUrl))
        }
        cpDatasources.put(cpName, dbDSMapping)
    }
  }

  def createTomcatDS(String dbType, String jdbcUrl) {
    DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource()
    def jdbcUrlToSet = dbType == "derby" ? jdbcUrl + ";create=true" : jdbcUrl
    ds.setUrl(jdbcUrlToSet)
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
    def jdbcUrlToSet = dbType == "derby" ? jdbcUrl + ";create=true" : jdbcUrl
    config.setJdbcUrl(jdbcUrlToSet)
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
    prepareConnectionPoolDatasources()
  }

  def cleanupSpec() {
    cpDatasources.values().each {
      it.values().each {
        datasource ->
          if (datasource instanceof Closeable) {
            datasource.close()
          }
      }
    }
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

    then:
    resultSet.next()
    resultSet.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName renameService ? dbName.toLowerCase() : driver
          operationName "${driver}.query"
          resourceName query
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            "$Tags.DB_USER" jdbcUserNames.get(driver)
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver   | connection                                              | renameService | query                                           | operation
    "h2"     | connectTo(driver, null)                                 | false         | "SELECT 3"                                      | "SELECT"
    "derby"  | connectTo(driver, null)                                 | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"
    "hsqldb" | connectTo(driver, null)                                 | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"
    "h2"     | connectTo(driver, connectionProps)                      | true          | "SELECT 3"                                      | "SELECT"
    "derby"  | connectTo(driver, connectionProps)                      | true          | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"
    "hsqldb" | connectTo(driver, connectionProps)                      | true          | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"
    "h2"     | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3"                                      | "SELECT"
    "derby"  | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"
    "hsqldb" | cpDatasources.get("tomcat").get(driver).getConnection() | true          | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"
    "h2"     | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3"                                      | "SELECT"
    "derby"  | cpDatasources.get("hikari").get(driver).getConnection() | true          | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"
    "hsqldb" | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"
    "h2"     | cpDatasources.get("c3p0").get(driver).getConnection()   | true          | "SELECT 3"                                      | "SELECT"
    "derby"  | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"
    "hsqldb" | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"
  }

  @Unroll
  def "prepared statement execute on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    PreparedStatement statement = connection.prepareStatement(query)
    ResultSet resultSet = runUnderTrace("parent") {
      assert statement.execute()
      return statement.resultSet
    }

    expect:
    resultSet.next()
    resultSet.getInt(1) == 3
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
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" jdbcUserNames.get(driver)
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | connection                                              | query                            | operation
    "h2"    | connectTo(driver, null)                                 | "SELECT 3"                       | "SELECT"
    "derby" | connectTo(driver, null)                                 | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
  }

  @Unroll
  def "prepared statement query on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    PreparedStatement statement = connection.prepareStatement(query)
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery()
    }

    expect:
    resultSet.next()
    resultSet.getInt(1) == 3
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
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" jdbcUserNames.get(driver)
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | connection                                              | query                            | operation
    "h2"    | connectTo(driver, null)                                 | "SELECT 3"                       | "SELECT"
    "derby" | connectTo(driver, null)                                 | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
  }

  @Unroll
  def "prepared call on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    CallableStatement statement = connection.prepareCall(query)
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery()
    }

    expect:
    resultSet.next()
    resultSet.getInt(1) == 3
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
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" jdbcUserNames.get(driver)
            "${Tags.DB_OPERATION}" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | connection                                              | query                            | operation
    "h2"    | connectTo(driver, null)                                 | "SELECT 3"                       | "SELECT"
    "derby" | connectTo(driver, null)                                 | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    "h2"    | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"                       | "SELECT"
    "derby" | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
  }

  @Unroll
  def "statement update on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    Statement statement = connection.createStatement()
    def sql = connection.nativeSQL(query)

    expect:
    runUnderTrace("parent") {
      return !statement.execute(sql)
    }
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
            // currently there is a bug in the instrumentation with
            // postgresql and mysql if the connection event is missed
            // since Connection.getClientInfo will not provide the username
            "$Tags.DB_USER" jdbcUserNames.get(driver)
            "${Tags.DB_OPERATION}" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver   | connection                                              | query                                                                           | operation
    "h2"     | connectTo(driver, null)                                 | "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"                   | "CREATE"
    "derby"  | connectTo(driver, null)                                 | "CREATE TABLE S_DERBY (id INTEGER not NULL, PRIMARY KEY ( id ))"                | "CREATE"
    "hsqldb" | connectTo(driver, null)                                 | "CREATE TABLE PUBLIC.S_HSQLDB (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    "h2"     | cpDatasources.get("tomcat").get(driver).getConnection() | "CREATE TABLE S_H2_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))"            | "CREATE"
    "derby"  | cpDatasources.get("tomcat").get(driver).getConnection() | "CREATE TABLE S_DERBY_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))"         | "CREATE"
    "hsqldb" | cpDatasources.get("tomcat").get(driver).getConnection() | "CREATE TABLE PUBLIC.S_HSQLDB_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"     | cpDatasources.get("hikari").get(driver).getConnection() | "CREATE TABLE S_H2_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))"            | "CREATE"
    "derby"  | cpDatasources.get("hikari").get(driver).getConnection() | "CREATE TABLE S_DERBY_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))"         | "CREATE"
    "hsqldb" | cpDatasources.get("hikari").get(driver).getConnection() | "CREATE TABLE PUBLIC.S_HSQLDB_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"     | cpDatasources.get("c3p0").get(driver).getConnection()   | "CREATE TABLE S_H2_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"              | "CREATE"
    "derby"  | cpDatasources.get("c3p0").get(driver).getConnection()   | "CREATE TABLE S_DERBY_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"           | "CREATE"
    "hsqldb" | cpDatasources.get("c3p0").get(driver).getConnection()   | "CREATE TABLE PUBLIC.S_HSQLDB_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }

  @Unroll
  def "prepared statement update on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    def sql = connection.nativeSQL(query)
    PreparedStatement statement = connection.prepareStatement(sql)

    expect:
    runUnderTrace("parent") {
      return statement.executeUpdate() == 0
    }
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
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            if (username != null) {
              "$Tags.DB_USER" username
            }
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | connection                                              | username | query                                                                    | operation
    "h2"    | connectTo(driver, null)                                 | null     | "CREATE TABLE PS_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"           | "CREATE"
    "derby" | connectTo(driver, null)                                 | "APP"    | "CREATE TABLE PS_DERBY (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    "h2"    | cpDatasources.get("tomcat").get(driver).getConnection() | null     | "CREATE TABLE PS_H2_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))"    | "CREATE"
    "derby" | cpDatasources.get("tomcat").get(driver).getConnection() | "APP"    | "CREATE TABLE PS_DERBY_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"    | cpDatasources.get("hikari").get(driver).getConnection() | null     | "CREATE TABLE PS_H2_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))"    | "CREATE"
    "derby" | cpDatasources.get("hikari").get(driver).getConnection() | "APP"    | "CREATE TABLE PS_DERBY_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"    | cpDatasources.get("c3p0").get(driver).getConnection()   | null     | "CREATE TABLE PS_H2_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"      | "CREATE"
    "derby" | cpDatasources.get("c3p0").get(driver).getConnection()   | "APP"    | "CREATE TABLE PS_DERBY_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }

  @Unroll
  def "connection constructor throwing then generating correct spans after recovery using #driver connection (prepare statement = #prepareStatement)"() {
    setup:
    Connection connection = null

    when:
    try {
      connection = new TestConnection(true)
    } catch (Exception e) {
      connection = connectTo(driver, null)
    }

    Statement statement = null
    ResultSet rs = runUnderTrace("parent") {
      if (prepareStatement) {
        statement = connection.prepareStatement(query)
        return statement.executeQuery()
      }

      statement = connection.createStatement()
      return statement.executeQuery(query)
    }

    then:
    rs.next()
    rs.getInt(1) == 3
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
          topLevel true
          tags {
            if (prepareStatement) {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            } else {
              "$Tags.COMPONENT" "java-jdbc-statement"
            }
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            if (username != null) {
              "$Tags.DB_USER" username
            }
            "$Tags.DB_OPERATION" operation
            defaultTags()
          }
        }
      }
    }

    cleanup:
    if (statement != null) {
      statement.close()
    }
    if (connection != null) {
      connection.close()
    }

    where:
    prepareStatement | driver  | url                                            | username | query                            | operation
    true             | "h2"    | "jdbc:h2:mem:" + dbName                        | null     | "SELECT 3;"                      | "SELECT"
    true             | "derby" | "jdbc:derby:memory:" + dbName + ";create=true" | "APP"    | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
    false            | "h2"    | "jdbc:h2:mem:" + dbName                        | null     | "SELECT 3;"                      | "SELECT"
    false            | "derby" | "jdbc:derby:memory:" + dbName + ";create=true" | "APP"    | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"
  }

  def "calling #datasource.class.simpleName getConnection generates a span when under existing trace"() {
    setup:
    assert datasource instanceof DataSource
    init?.call(datasource)

    when:
    datasource.getConnection().close()

    then:
    !TEST_WRITER.any { it.any { it.operationName.toString() == "database.connection" } }
    TEST_WRITER.clear()

    when:
    runUnderTrace("parent") {
      datasource.getConnection().close()
    }

    then:
    assertTraces(1) {
      trace(recursive ? 3 : 2) {
        basicSpan(it, "parent")

        span {
          operationName "database.connection"
          resourceName "${datasource.class.simpleName}.getConnection"
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-jdbc-connection"
            defaultTags()
          }
        }
        if (recursive) {
          span {
            operationName "database.connection"
            resourceName "${datasource.class.simpleName}.getConnection"
            childOf span(1)
            tags {
              "$Tags.COMPONENT" "java-jdbc-connection"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    datasource                               | init
    new JdbcDataSource()                     | { ds -> ds.setURL(jdbcUrls.get("h2")) }
    new EmbeddedDataSource()                 | { ds -> ds.jdbcurl = jdbcUrls.get("derby") }
    cpDatasources.get("hikari").get("h2")    | null
    cpDatasources.get("hikari").get("derby") | null
    cpDatasources.get("c3p0").get("h2")      | null
    cpDatasources.get("c3p0").get("derby")   | null

    // Tomcat's pool doesn't work because the getConnection method is
    // implemented in a parent class that doesn't implement DataSource

    recursive = datasource instanceof EmbeddedDataSource
  }

  def "test getClientInfo exception"() {
    setup:
    Connection connection = new TestConnection(false)

    when:
    Statement statement = null
    runUnderTrace("parent") {
      statement = connection.createStatement()
      return statement.executeQuery(query)
    }

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "${database}.query"
          serviceName database
          resourceName query
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" database
            "$Tags.DB_OPERATION" CharSequence
            defaultTags()
          }
        }
      }
    }

    cleanup:
    if (statement != null) {
      statement.close()
    }
    if (connection != null) {
      connection.close()
    }

    where:
    database = "testdb"
    query = "testing 123"
  }

  @Unroll
  def "#connectionPoolName connections should be cached in case of wrapped connections"() {
    setup:
    String dbType = "hsqldb"
    DataSource ds = createDS(connectionPoolName, dbType, jdbcUrls.get(dbType))
    String query = "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    int numQueries = 5
    Connection connection = null
    Statement statement = null
    ResultSet rs = null
    int[] res = new int[numQueries]

    when:
    for (int i = 0; i < numQueries; ++i) {
      try {
        connection = ds.getConnection()
        statement = connection.prepareStatement(query)
        rs = statement.executeQuery()
        if (rs.next()) {
          res[i] = rs.getInt(1)
        } else {
          res[i] = 0
        }
      } finally {
        connection.close()
      }
    }

    then:
    for (int i = 0; i < numQueries; ++i) {
      res[i] == 3
    }
    assertTraces(5) {
      trace(1) {
        span {
          operationName "${dbType}.query"
          serviceName dbType
          resourceName query
          spanType DDSpanTypes.SQL
          errored false
          topLevel true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" dbType
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            "$Tags.DB_USER" "SA"
            "$Tags.DB_OPERATION" "SELECT"
            defaultTags()
          }
        }
      }
      for (int i = 1; i < numQueries; ++i) {
        trace(1) {
          span {
            operationName "${dbType}.query"
            serviceName dbType
            resourceName query
            spanType DDSpanTypes.SQL
            errored false
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" dbType
              "$Tags.DB_INSTANCE" dbName.toLowerCase()
              "$Tags.DB_USER" "SA"
              "$Tags.DB_OPERATION" "SELECT"
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    if (ds instanceof Closeable) {
      ds.close()
    }

    where:
    connectionPoolName | _
    "hikari"           | _
    "tomcat"           | _
    "c3p0"             | _
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
