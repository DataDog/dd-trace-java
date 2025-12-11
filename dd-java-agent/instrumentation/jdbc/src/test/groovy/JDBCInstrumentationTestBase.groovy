import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_POOL_WAITING_ENABLED

import com.mchange.v2.c3p0.ComboPooledDataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.Statement
import java.time.Duration
import javax.sql.DataSource
import org.apache.commons.dbcp2.BasicDataSource
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.PooledObjectFactory
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.derby.jdbc.EmbeddedDataSource
import org.h2.jdbcx.JdbcDataSource
import spock.lang.Shared
import test.TestConnection
import test.WrappedConnection

abstract class JDBCInstrumentationTest extends VersionedNamingTestBase {

  @Shared
  def dbName = "jdbcUnitTest"

  @Shared
  private Map<String, String> jdbcUrls = [
    "h2"        : "jdbc:h2:mem:$dbName",
    "derby"     : "jdbc:derby:memory:$dbName",
    "hsqldb"    : "jdbc:hsqldb:mem:$dbName",
  ]

  @Shared
  private Map<String, String> jdbcDriverClassNames = [
    "h2"        : "org.h2.Driver",
    "derby"     : "org.apache.derby.jdbc.EmbeddedDriver",
    "hsqldb"    : "org.hsqldb.jdbc.JDBCDriver",
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

  def createDbcp2DS(String dbType, String jdbcUrl) {
    BasicDataSource ds = new BasicDataSource()
    def jdbcUrlToSet = dbType == "derby" ? jdbcUrl + ";create=true" : jdbcUrl
    ds.setUrl(jdbcUrlToSet)
    ds.setDriverClassName(jdbcDriverClassNames.get(dbType))
    String username = jdbcUserNames.get(dbType)
    if (username != null) {
      ds.setUsername(username)
    }
    ds.setPassword(jdbcPasswords.get(dbType))
    ds.setMaxTotal(1) // to test proper caching, having > 1 max active connection will be hard to
    // determine whether the connection is properly cached
    ds.setMaxWait(Duration.ofMillis(1000))
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
    config.setConnectionTimeout(1000)

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
    if (connectionPoolName == "dbcp2") {
      ds = createDbcp2DS(dbType, jdbcUrl)
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
    injectSysConfig(JDBC_POOL_WAITING_ENABLED, "true")
  }

  def setupSpec() {
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
  }

  def "basic statement with #connection.getClass().getCanonicalName() on #driver generates spans"() {
    setup:
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

    when:
    Statement statement = connection.createStatement()
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery(query)
    }

    then:
    def addDbmTag = dbmTraceInjected()
    resultSet.next()
    resultSet.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName renameService ? dbName.toLowerCase() : service(driver)
          operationName this.operation(driver)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "java-jdbc-statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            "$Tags.DB_USER" jdbcUserNames.get(driver)
            "$Tags.DB_OPERATION" operation
            if (addDbmTag) {
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
            }
            if (conPoolType == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver   | conPoolType | connection                                                 | renameService | query                                           | operation | obfuscatedQuery
    "h2"     | ""          | connectTo(driver)                                          | false         | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | ""          | connectTo(driver)                                          | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | ""          | connectTo(driver)                                          | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | ""          | connectTo(driver)                                          | true          | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | ""          | connectTo(driver)                                          | true          | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | ""          | connectTo(driver)                                          | true          | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | ""          | connectTo(driver, connectionProps)                         | true          | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | ""          | connectTo(driver, connectionProps)                         | true          | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | ""          | connectTo(driver, connectionProps)                         | true          | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | ""          | new WrappedConnection(connectTo(driver))                   | false         | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | ""          | new WrappedConnection(connectTo(driver))                   | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | ""          | new WrappedConnection(connectTo(driver))                   | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | ""          | new WrappedConnection(connectTo(driver))                   | true          | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | ""          | new WrappedConnection(connectTo(driver))                   | true          | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | ""          | new WrappedConnection(connectTo(driver))                   | true          | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | false         | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | true          | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | false         | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | true          | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    "h2"     | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | true          | "SELECT 3"                                      | "SELECT"  | "SELECT ?"
    "derby"  | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | false         | "SELECT 3 FROM SYSIBM.SYSDUMMY1"                | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "hsqldb" | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | false         | "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS" | "SELECT"  | "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
  }

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
          operationName this.operation(driver)
          serviceName service(driver)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
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
            if (conPoolType == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | conPoolType | connection                                                 | query                            | operation | obfuscatedQuery
    "h2"    | ""          | connectTo(driver)                                          | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | ""          | connectTo(driver)                                          | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
  }

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
          operationName this.operation(driver)
          serviceName service(driver)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
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
            if (conPoolType == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | conPoolType | connection                                                 | query                            | operation | obfuscatedQuery
    "h2"    | ""          | connectTo(driver)                                          | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | ""          | connectTo(driver)                                          | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
  }

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
          operationName this.operation(driver)
          serviceName service(driver)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
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
            if (conPoolType == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | conPoolType | connection                                                 | query                            | operation | obfuscatedQuery
    "h2"    | ""          | connectTo(driver)                                          | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | ""          | connectTo(driver)                                          | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    "h2"    | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"                       | "SELECT"  | "SELECT ?"
    "derby" | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
  }

  def "statement update on #driver with #connection.getClass().getCanonicalName() generates a span"() {
    setup:
    Statement statement = connection.createStatement()
    def sql = connection.nativeSQL(query)

    expect:
    runUnderTrace("parent") {
      return !statement.execute(sql)
    }
    def addDbmTag = dbmTraceInjected()
    statement.updateCount == 0
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName this.operation(driver)
          serviceName service(driver)
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
            if (addDbmTag) {
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
            }
            if (conPoolType == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver   | conPoolType | connection                                                 | query                                                                           | operation
    "h2"     | ""          | connectTo(driver)                                          | "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"                   | "CREATE"
    "derby"  | ""          | connectTo(driver)                                          | "CREATE TABLE S_DERBY (id INTEGER not NULL, PRIMARY KEY ( id ))"                | "CREATE"
    "hsqldb" | ""          | connectTo(driver)                                          | "CREATE TABLE PUBLIC.S_HSQLDB (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    "h2"     | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE S_H2_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))"            | "CREATE"
    "derby"  | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE S_DERBY_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))"         | "CREATE"
    "hsqldb" | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE PUBLIC.S_HSQLDB_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"     | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE S_H2_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))"            | "CREATE"
    "derby"  | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE S_DERBY_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))"         | "CREATE"
    "hsqldb" | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE PUBLIC.S_HSQLDB_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"     | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE S_H2_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"              | "CREATE"
    "derby"  | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE S_DERBY_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"           | "CREATE"
    "hsqldb" | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE PUBLIC.S_HSQLDB_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }

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
          operationName this.operation(driver)
          serviceName service(driver)
          resourceName query
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            if (conPoolType == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            if (username != null) {
              "$Tags.DB_USER" username
            }
            "$Tags.DB_OPERATION" operation
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver  | conPoolType | connection                                                 | username | query                                                                    | operation
    "h2"    | ""          | connectTo(driver)                                          | null     | "CREATE TABLE PS_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"           | "CREATE"
    "derby" | ""          | connectTo(driver)                                          | "APP"    | "CREATE TABLE PS_DERBY (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    "h2"    | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | null     | "CREATE TABLE PS_H2_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))"    | "CREATE"
    "derby" | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "APP"    | "CREATE TABLE PS_DERBY_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"    | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | null     | "CREATE TABLE PS_H2_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))"    | "CREATE"
    "derby" | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "APP"    | "CREATE TABLE PS_DERBY_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    "h2"    | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | null     | "CREATE TABLE PS_H2_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"      | "CREATE"
    "derby" | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "APP"    | "CREATE TABLE PS_DERBY_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }

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
    def addDbmTag = dbmTraceInjected()
    rs.next()
    rs.getInt(1) == 3
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName this.operation(driver)
          serviceName service(driver)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
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
            if (addDbmTag && !prepareStatement) {
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
            }
            peerServiceFrom(Tags.DB_INSTANCE)
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
    prepareStatement | driver  | url                                            | username | query                            | operation | obfuscatedQuery
    true             | "h2"    | "jdbc:h2:mem:" + dbName                        | null     | "SELECT 3;"                      | "SELECT"  | "SELECT ?"
    true             | "derby" | "jdbc:derby:memory:" + dbName + ";create=true" | "APP"    | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
    false            | "h2"    | "jdbc:h2:mem:" + dbName                        | null     | "SELECT 3;"                      | "SELECT"  | "SELECT ?"
    false            | "derby" | "jdbc:derby:memory:" + dbName + ";create=true" | "APP"    | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT"  | "SELECT ? FROM SYSIBM.SYSDUMMY1"
  }

  def "calling #datasource.class.simpleName getConnection generates a span when under existing trace"() {
    setup:
    assert datasource instanceof DataSource
    init?.call(datasource)

    when:
    datasource.getConnection().close()

    then:
    !TEST_WRITER.any {
      it.any {
        it.operationName.toString() == "database.connection"
      }
    }
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
            defaultTagsNoPeerService()
          }
        }
        if (recursive) {
          span {
            operationName "database.connection"
            resourceName "${datasource.class.simpleName}.getConnection"
            childOf span(1)
            tags {
              "$Tags.COMPONENT" "java-jdbc-connection"
              defaultTagsNoPeerService()
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
    def addDbmTag = dbmTraceInjected()
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName this.operation(database)
          serviceName service(database)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.COMPONENT" "java-jdbc-statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" database
            "$Tags.DB_OPERATION" CharSequence
            if (addDbmTag) {
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
            }
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
    obfuscatedQuery = "testing ?"
  }

  def "#connectionPoolName connections should be cached in case of wrapped connections"() {
    setup:
    String dbType = "hsqldb"
    DataSource ds = createDS(connectionPoolName, dbType, jdbcUrls.get(dbType))
    String query = "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS"
    String obfuscatedQuery = "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"
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
          operationName this.operation(dbType)
          serviceName service(dbType)
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" dbType
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            "$Tags.DB_USER" "SA"
            "$Tags.DB_OPERATION" "SELECT"
            if (connectionPoolName == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
      }
      for (int i = 1; i < numQueries; ++i) {
        trace(1) {
          span {
            operationName this.operation(dbType)
            serviceName service(dbType)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            errored false
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" dbType
              "$Tags.DB_INSTANCE" dbName.toLowerCase()
              "$Tags.DB_USER" "SA"
              "$Tags.DB_OPERATION" "SELECT"
              if (connectionPoolName == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              peerServiceFrom(Tags.DB_INSTANCE)
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

  def "#connectionPoolName should have pool.waiting span when pool exhausted for #exhaustPoolForMillis with exception thrown #expectException"() {
    setup:
    String dbType = "hsqldb"
    DataSource ds = createDS(connectionPoolName, dbType, jdbcUrls.get(dbType))

    if (exhaustPoolForMillis != null) {
      def saturatedConnection = ds.getConnection()
      new Thread(() -> {
        Thread.sleep(exhaustPoolForMillis)
        saturatedConnection.close()
      }, "saturated connection closer").start()
    }

    when:
    Throwable timedOutException = null
    runUnderTrace("parent") {
      try {
        ds.getConnection().close()
      } catch (SQLTransientConnectionException e) {
        if (e.getMessage().contains("request timed out after")) {
          // Hikari, newer
          timedOutException = e
        } else {
          throw e
        }
      } catch (SQLTimeoutException e) {
        // Hikari, older
        timedOutException = e
      } catch (SQLException e) {
        if (e.getMessage().contains("pool error Timeout waiting for idle object")) {
          // dbcp2
          timedOutException = e
        } else {
          throw e
        }
      }
    }

    then:
    assertTraces(1) {
      trace(connectionPoolName == "dbcp2" ? 4 : 3) {
        basicSpan(it, "parent")

        span {
          operationName "database.connection"
          resourceName "${ds.class.simpleName}.getConnection"
          childOf span(0)
          errored timedOutException != null
          tags {
            "$Tags.COMPONENT" "java-jdbc-connection"
            defaultTagsNoPeerService()
            if (timedOutException) {
              errorTags(timedOutException)
            }
          }
        }

        // dbcp2 will have two database.connection spans
        if (connectionPoolName == "dbcp2") {
          span {
            operationName "database.connection"
            resourceName "PoolingDataSource.getConnection"
            childOf span(1)
            errored timedOutException != null
            tags {
              "$Tags.COMPONENT" "java-jdbc-connection"
              defaultTagsNoPeerService()
              if (timedOutException) {
                errorTags(timedOutException)
              }
            }
          }
        }

        span {
          operationName "pool.waiting"
          resourceName "${connectionPoolName}.waiting"
          childOf span(connectionPoolName == "dbcp2" ? 2 : 1)
          tags {
            "$Tags.COMPONENT" "java-jdbc-pool-waiting"
            if (connectionPoolName == "hikari") {
              "$Tags.DB_POOL_NAME" String
            }
            defaultTagsNoPeerService()
          }
        }
      }
    }
    assert expectException == (timedOutException != null)

    cleanup:
    if (ds instanceof Closeable) {
      ds.close()
    }

    where:
    connectionPoolName | exhaustPoolForMillis | expectException
    "hikari"           | 500                  | false
    "dbcp2"            | 500                  | false
    "hikari"           | 1500                 | true
    "dbcp2"            | 1500                 | true
  }

  def "Ensure LinkedBlockingDeque.pollFirst called outside of DBCP2 does not create spans"() {
    setup:
    def pool = new GenericObjectPool<>(new PooledObjectFactory() {

      @Override
      void activateObject(PooledObject p) throws Exception {
      }

      @Override
      void destroyObject(PooledObject p) throws Exception {
      }

      @Override
      PooledObject makeObject() throws Exception {
        return new DefaultPooledObject(new Object())
      }

      @Override
      void passivateObject(PooledObject p) throws Exception {
      }

      @Override
      boolean validateObject(PooledObject p) {
        return false
      }
    })
    pool.setMaxTotal(1)

    when:
    def exhaustPoolForMillis = 500
    def saturatedConnection = pool.borrowObject()
    new Thread(() -> {
      Thread.sleep(exhaustPoolForMillis)
      pool.returnObject(saturatedConnection)
    }, "saturated connection closer").start()

    pool.borrowObject(1000)

    then:
    TEST_WRITER.size() == 0
  }

  Driver driverFor(String db) {
    return newDriver(jdbcDriverClassNames.get(db))
  }

  Connection connectTo(String db, Properties properties = null) {
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

  @Override
  final String service() {
    return null
  }

  @Override
  final String operation() {
    return null
  }

  protected abstract String service(String dbType)

  protected abstract String operation(String dbType)

  protected abstract boolean dbmTraceInjected()
}

class JDBCInstrumentationV0Test extends JDBCInstrumentationTest {

  @Override
  int version() {
    return 0
  }

  @Override
  protected String service(String dbType) {
    return dbType
  }

  @Override
  protected String operation(String dbType) {
    return "${dbType}.query"
  }

  @Override
  protected boolean dbmTraceInjected() {
    return false
  }
}

class JDBCInstrumentationV1ForkedTest extends JDBCInstrumentationTest {

  @Override
  int version() {
    return 1
  }

  @Override
  protected String service(String dbType) {
    return Config.get().getServiceName()
  }

  @Override
  protected String operation(String dbType) {
    return "${dbType}.query"
  }

  @Override
  protected boolean dbmTraceInjected() {
    return false
  }
}

class JDBCInstrumentationDBMTraceInjectedForkedTest extends JDBCInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.dbm.propagation.mode", "full")
  }

  @Override
  int version() {
    return 1
  }

  @Override
  protected String service(String dbType) {
    return Config.get().getServiceName()
  }

  @Override
  protected String operation(String dbType) {
    return "${dbType}.query"
  }

  @Override
  protected boolean dbmTraceInjected() {
    return true
  }
}
