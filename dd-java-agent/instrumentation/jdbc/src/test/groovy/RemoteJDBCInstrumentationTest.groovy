import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_TRACE_PREPARED_STATEMENTS

import com.mchange.v2.c3p0.ComboPooledDataSource
import com.microsoft.sqlserver.jdbc.SQLServerException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.naming.v1.DatabaseNamingV1
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared

abstract class RemoteJDBCInstrumentationTest extends VersionedNamingTestBase {
  static final String POSTGRESQL = "postgresql"
  static final String MYSQL = "mysql"
  static final String SQLSERVER = "sqlserver"
  static final String ORACLE = "oracle"

  @Shared
  private Map<String, String> dbName = [
    (POSTGRESQL): "jdbcUnitTest",
    (MYSQL)     : "jdbcUnitTest",
    (SQLSERVER) : "master",
    (ORACLE) : "freepdb1",
  ]

  @Shared
  private Map<String, String> jdbcUrls = [
    (POSTGRESQL) : "jdbc:postgresql://localhost:5432/" + dbName.get(POSTGRESQL),
    (MYSQL)      : "jdbc:mysql://localhost:3306/" + dbName.get(MYSQL),
    (SQLSERVER)  : "jdbc:sqlserver://localhost:1433/" + dbName.get(SQLSERVER),
    (ORACLE)  : "jdbc:oracle:thin:@//localhost:1521/" + dbName.get(ORACLE),
  ]

  @Shared
  private Map<String, String> jdbcDriverClassNames = [
    (POSTGRESQL): "org.postgresql.Driver",
    (MYSQL)     : "com.mysql.jdbc.Driver",
    (SQLSERVER) : "com.microsoft.sqlserver.jdbc.SQLServerDriver",
    (ORACLE) : "oracle.jdbc.OracleDriver",
  ]

  @Shared
  private Map<String, String> jdbcUserNames = [
    (POSTGRESQL): "sa",
    (MYSQL)     : "sa",
    (SQLSERVER)  : "sa",
    (ORACLE)  : "testuser",
  ]

  @Shared
  private Map<String, String> jdbcPasswords = [
    (MYSQL)     : "sa",
    (POSTGRESQL): "sa",
    (SQLSERVER) : "Datad0g_",
    (ORACLE) : "testPassword",
  ]

  @Shared
  def postgres
  @Shared
  def mysql
  @Shared
  def sqlserver
  @Shared
  def oracle

  // JDBC Connection pool name (i.e. HikariCP) -> Map<dbName, Datasource>
  @Shared
  private Map<String, Map<String, DataSource>> cpDatasources = new HashMap<>()

  def peerConnectionProps(String db){
    def props = new Properties()
    props.setProperty("user", jdbcUserNames.get(db))
    props.setProperty("password", jdbcPasswords.get(db))
    return props
  }

  protected getDbType(String dbType){
    return dbType
  }

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
    // POSTGRESQL
    postgres = new PostgreSQLContainer("postgres:11.2")
      .withDatabaseName(dbName.get(POSTGRESQL)).withUsername(jdbcUserNames.get(POSTGRESQL)).withPassword(jdbcPasswords.get(POSTGRESQL))
    postgres.start()
    PortUtils.waitForPortToOpen(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put(POSTGRESQL, "${postgres.getJdbcUrl()}")

    // MYSQL
    mysql = new MySQLContainer("mysql:8.0")
      .withDatabaseName(dbName.get(MYSQL)).withUsername(jdbcUserNames.get(MYSQL)).withPassword(jdbcPasswords.get(MYSQL))
    // https://github.com/testcontainers/testcontainers-java/issues/914
    mysql.addParameter("TC_MY_CNF", null)
    mysql.start()
    PortUtils.waitForPortToOpen(mysql.getHost(), mysql.getMappedPort(MySQLContainer.MYSQL_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put(MYSQL, "${mysql.getJdbcUrl()}")

    // SQLSERVER
    sqlserver = new MSSQLServerContainer(MSSQLServerContainer.IMAGE).acceptLicense().withPassword(jdbcPasswords.get(SQLSERVER))
    sqlserver.start()
    PortUtils.waitForPortToOpen(sqlserver.getHost(), sqlserver.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put(SQLSERVER, "${sqlserver.getJdbcUrl()};DatabaseName=${dbName.get(SQLSERVER)}")

    // ORACLE
    // Earlier Oracle version images (oracle-xe) don't work on arm64
    DockerImageName oracleImage = DockerImageName.parse("gvenzl/oracle-free:23.5-slim-faststart").asCompatibleSubstituteFor("gvenzl/oracle-xe")
    oracle = new OracleContainer(oracleImage)
      .withStartupTimeout(Duration.ofMinutes(5)).withUsername(jdbcUserNames.get(ORACLE)).withPassword(jdbcPasswords.get(ORACLE))
    oracle.start()
    jdbcUrls.put(ORACLE, "${oracle.getJdbcUrl()}".replace("xepdb1", dbName.get(ORACLE)))

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
    sqlserver?.close()
    oracle?.close()
  }

  def "basic statement on #driver with #pool generates spans"() {
    setup:
    Connection connection = setupConnection(pool, driver)
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

    when:
    Statement statement = connection.createStatement()
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery(query)
    }
    TEST_WRITER.waitForTraces(1)

    then:
    def addDbmTag = dbmTraceInjected()
    resultSet.next()
    resultSet.getInt(1) == 3
    if (driver == POSTGRESQL || driver == MYSQL || driver == ORACLE || !addDbmTag) {
      assertTraces(1) {
        trace(2) {
          basicSpan(it, "parent")
          span {
            serviceName renameService ? dbName.get(driver).toLowerCase() : service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" operation
              if (usingHikari) {
                "$Tags.DB_POOL_NAME" String
              }
              if (addDbmTag) {
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    } else {
      assertTraces(1) {
        trace(3) {
          basicSpan(it, "parent")
          span {
            serviceName renameService ? dbName.get(driver).toLowerCase() : service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" operation
              if (usingHikari) {
                "$Tags.DB_POOL_NAME" String
              }
              if (addDbmTag) {
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
          span {
            serviceName renameService ? dbName.get(driver).toLowerCase() : service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName "set context_info ?"
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" "set"
              if (usingHikari) {
                "$Tags.DB_POOL_NAME" String
              }
              "dd.instrumentation" true
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver     | pool     | renameService | query                   | operation | obfuscatedQuery         | usingHikari
    MYSQL      | null     | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | null     | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | false
    SQLSERVER  | null     | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | null     | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | "tomcat" | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | "tomcat" | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | false
    SQLSERVER  | "tomcat" | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | "tomcat" | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | "hikari" | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    POSTGRESQL | "hikari" | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | true
    SQLSERVER  | "hikari" | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    ORACLE     | "hikari" | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | true
    MYSQL      | "c3p0"   | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | "c3p0"   | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | false
    SQLSERVER  | "c3p0"   | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | "c3p0"   | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
  }

  def "prepared statement execute on #driver with #pool generates a span"() {
    setup:
    Connection connection = setupConnection(pool, driver)
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
    def addDbmTag = dbmTraceInjected()
    if (driver == SQLSERVER && addDbmTag){
      assertTraces(1) {
        trace(3) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" operation
              if (usingHikari) {
                "$Tags.DB_POOL_NAME" String
              }
              if (addDbmTag) {
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
          span {
            serviceName service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName "set context_info ?"
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" "set"
              if (usingHikari) {
                "$Tags.DB_POOL_NAME" String
              }
              "dd.instrumentation" true
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    } else {
      assertTraces(1) {
        trace(2) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              // only set when there is an out of proc instance (postgresql, mysql)
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" operation
              if (usingHikari) {
                "$Tags.DB_POOL_NAME" String
              }
              if (this.dbmTracePreparedStatements(this.getDbType(driver))){
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
                if (driver == POSTGRESQL) {
                  "$InstrumentationTags.INSTRUMENTATION_TIME_MS" Long
                }
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver     | pool     | query                   | operation | obfuscatedQuery         | usingHikari
    MYSQL      | null     | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | null     | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | false
    SQLSERVER  | null     | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | null     | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | "tomcat" | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | "tomcat" | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | false
    SQLSERVER  | "tomcat" | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | "tomcat" | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | "hikari" | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    POSTGRESQL | "hikari" | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | true
    SQLSERVER  | "hikari" | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    ORACLE     | "hikari" | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | true
    MYSQL      | "c3p0"   | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | "c3p0"   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | false
    SQLSERVER  | "c3p0"   | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | "c3p0"   | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
  }

  def "prepared statement query on #driver with #pool generates a span"() {
    setup:
    Connection connection = setupConnection(pool, driver)
    PreparedStatement statement = connection.prepareStatement(query)

    when:
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    resultSet.next()
    resultSet.getInt(1) == 3

    def addDbmTag = dbmTraceInjected()
    if (driver == SQLSERVER && addDbmTag){
      assertTraces(1) {
        trace(3) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              // only set when there is an out of proc instance (postgresql, mysql)
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" operation
              if (conPoolType == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
          span {
            serviceName service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName "set context_info ?"
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" "set"
              if (conPoolType == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              "dd.instrumentation" true
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    } else {
      assertTraces(1) {
        trace(2) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              // only set when there is an out of proc instance (postgresql, mysql)
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" operation
              if (pool == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              if (this.dbmTracePreparedStatements(driver)){
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
                if (driver == POSTGRESQL) {
                  "$InstrumentationTags.INSTRUMENTATION_TIME_MS" Long
                }
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver     | pool       | query                   | operation | obfuscatedQuery
    MYSQL      | null       | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | null       | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | null       | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | null       | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
    MYSQL      | "tomcat"   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "tomcat"   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "tomcat"   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "tomcat"   | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
    MYSQL      | "hikari"   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "hikari"   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "hikari"   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "hikari"   | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
    MYSQL      | "c3p0"     | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "c3p0"     | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "c3p0"     | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "c3p0"     | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
  }

  def "prepared call on #driver with #pool generates a span"() {
    setup:
    Connection connection = setupConnection(pool, driver)
    CallableStatement statement = connection.prepareCall(query)

    when:
    ResultSet resultSet = runUnderTrace("parent") {
      return statement.executeQuery()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    resultSet.next()
    resultSet.getInt(1) == 3
    def addDbmTag = dbmTraceInjected()
    if (driver == SQLSERVER && addDbmTag){
      assertTraces(1) {
        trace(3) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "${Tags.DB_OPERATION}" operation
              if (conPoolType == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
              defaultTags()
            }
          }
          span {
            serviceName service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName "set context_info ?"
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" "set"
              "dd.instrumentation" true
              if (conPoolType == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    } else {
      assertTraces(1) {
        trace(2) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName obfuscatedQuery
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-prepared_statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              // only set when there is an out of proc instance (postgresql, mysql)
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "${Tags.DB_OPERATION}" operation
              if (pool == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              if (this.dbmTracePreparedStatements(this.getDbType(driver))){
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
                if (driver == POSTGRESQL) {
                  "$InstrumentationTags.INSTRUMENTATION_TIME_MS" Long
                }
              }
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    statement.close()
    connection.close()

    where:
    driver     | pool     | query                   | operation | obfuscatedQuery
    MYSQL      | null     | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | null     | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | null     | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | null     | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
    MYSQL      | "tomcat" | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "tomcat" | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "tomcat" | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "tomcat" | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
    MYSQL      | "hikari" | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "hikari" | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "hikari" | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "hikari" | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
    MYSQL      | "c3p0"   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "c3p0"   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "c3p0"   | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "c3p0"   | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
  }

  def "statement update on #driver with #pool generates a span"() {
    setup:
    Connection connection = setupConnection(pool, driver)
    Statement statement = connection.createStatement()
    def sql = connection.nativeSQL(query)

    when:
    runUnderTrace("parent") {
      return !statement.execute(sql)
    }
    TEST_WRITER.waitForTraces(1)

    then:
    def addDbmTag = dbmTraceInjected()
    statement.updateCount == 0
    if (driver == POSTGRESQL || driver == MYSQL || driver == ORACLE || !dbmTraceInjected()) {
      assertTraces(1) {
        trace(2) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName query
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              // only set when there is an out of proc instance (postgresql, mysql)
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "${Tags.DB_OPERATION}" operation
              if (addDbmTag) {
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
              }
              if (pool == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    } else {
      assertTraces(1) {
        trace(3) {
          basicSpan(it, "parent")
          span {
            operationName this.operation(this.getDbType(driver))
            serviceName service(driver)
            resourceName query
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              // only set when there is an out of proc instance (postgresql, mysql)
              "$Tags.PEER_HOSTNAME" String
              // currently there is a bug in the instrumentation with
              // postgresql and mysql if the connection event is missed
              // since Connection.getClientInfo will not provide the username
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "${Tags.DB_OPERATION}" operation
              if (addDbmTag) {
                "$InstrumentationTags.DBM_TRACE_INJECTED" true
              }
              if (conPoolType == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              "$InstrumentationTags.DBM_TRACE_INJECTED" true
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
          span {
            serviceName service(driver)
            operationName this.operation(this.getDbType(driver))
            resourceName "set context_info ?"
            spanType DDSpanTypes.SQL
            childOf span(0)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "java-jdbc-statement"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.DB_TYPE" this.getDbType(driver)
              "$Tags.DB_INSTANCE" dbName.get(driver).toLowerCase()
              "$Tags.PEER_HOSTNAME" String
              "$Tags.DB_USER" { it == null || it == jdbcUserNames.get(driver) }
              "$Tags.DB_OPERATION" "set"
              if (conPoolType == "hikari") {
                "$Tags.DB_POOL_NAME" String
              }
              "dd.instrumentation" true
              peerServiceFrom(Tags.DB_INSTANCE)
              defaultTags()
            }
          }
        }
      }
    }


    cleanup:
    statement.close()
    connection.close()

    where:
    driver     | pool     | query                                                                                   | operation
    MYSQL      | null     | "CREATE TEMPORARY TABLE s_test_ (id INTEGER not NULL, PRIMARY KEY ( id ))"              | "CREATE"
    POSTGRESQL | null     | "CREATE TEMPORARY TABLE s_test (id INTEGER not NULL, PRIMARY KEY ( id ))"               | "CREATE"
    SQLSERVER  | null     | "CREATE TABLE #s_test_ (id INTEGER not NULL, PRIMARY KEY ( id ))"                       | "CREATE"
    ORACLE     | null     | "CREATE GLOBAL TEMPORARY TABLE s_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    MYSQL      | "tomcat" | "CREATE TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    POSTGRESQL | "tomcat" | "CREATE TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    SQLSERVER  | "tomcat" | "CREATE TABLE #s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))"                 | "CREATE"
    ORACLE     | "tomcat" | "CREATE GLOBAL TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    MYSQL      | "hikari" | "CREATE TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    POSTGRESQL | "hikari" | "CREATE TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    SQLSERVER  | "hikari" | "CREATE TABLE #s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))"                 | "CREATE"
    ORACLE     | "hikari" | "CREATE GLOBAL TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    MYSQL      | "c3p0"   | "CREATE TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"          | "CREATE"
    POSTGRESQL | "c3p0"   | "CREATE TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"          | "CREATE"
    SQLSERVER  | "c3p0"   | "CREATE TABLE #s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"                   | "CREATE"
    ORACLE     | "c3p0"   | "CREATE GLOBAL TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }


  def "prepared procedure call with return value on #driver with #pool does not hang"() {
    setup:
    Connection connection = setupConnection(pool, driver)
    injectSysConfig("dd.dbm.propagation.mode", "full")

    CallableStatement upperProc = connection.prepareCall(query)
    upperProc.registerOutParameter(1, Types.VARCHAR)
    upperProc.setString(2, "hello world")
    when:
    runUnderTrace("parent") {
      return upperProc.execute()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    assert upperProc.getString(1) == "HELLO WORLD"
    cleanup:
    upperProc.close()
    connection.close()

    where:
    driver     | pool     | query
    POSTGRESQL | "hikari" | "{ ? = call upper( ? ) }"
    MYSQL      | "hikari" | "{ ? = call upper( ? ) }"
    POSTGRESQL | "tomcat" | "{ ? = call upper( ? ) }"
    MYSQL      | "tomcat" | "{ ? = call upper( ? ) }"
    POSTGRESQL | "c3p0"   | "{ ? = call upper( ? ) }"
    MYSQL      | "c3p0"   | "{ ? = call upper( ? ) }"
    POSTGRESQL | null     | "{ ? = call upper( ? ) }"
    MYSQL      | null     | "{ ? = call upper( ? ) }"
  }

  def "prepared procedure call on #driver with #pool does not hang"() {
    setup:
    Connection connection = setupConnection(pool, driver)

    String createSql
    if (driver == "postgresql") {
      createSql =
        """
    CREATE OR REPLACE PROCEDURE dummy(inout res integer)
    LANGUAGE SQL
    AS \$\$
        SELECT 1;
    \$\$;
    """
    } else if (driver == "mysql") {
      createSql =
        """
    CREATE PROCEDURE IF NOT EXISTS dummy(inout res int)
    BEGIN
        SELECT 1;
    END
    """
    } else if (driver == "sqlserver") {
      createSql =
        """
    CREATE PROCEDURE dummy @res integer output
    AS
    BEGIN
        SELECT 1;
    END
    """
    } else {
      assert false
    }

    if (driver.equals("postgresql") && connection.getMetaData().getDatabaseMajorVersion() <= 11) {
      // Skip test for older versions of PG that don't support out on procedure
      return
    }

    // object already exists (no IF NOT EXISTS in SQL Server)
    try {
      connection.prepareCall(createSql).execute()
    } catch (SQLServerException ex) {}

    injectSysConfig("dd.dbm.propagation.mode", "full")
    CallableStatement proc = connection.prepareCall(query)
    proc.setInt(1,1)
    proc.registerOutParameter(1, Types.INTEGER)
    when:
    runUnderTrace("parent") {
      return proc.execute()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    assert proc.getInt(1) == 1

    cleanup:
    if (proc != null) {
      proc.close()
    }
    connection.close()

    where:
    driver     | pool     | query
    POSTGRESQL | "hikari" | "CALL dummy(?)"
    MYSQL      | "hikari" | "CALL dummy(?)"
    SQLSERVER  | "hikari" | "{CALL dummy(?)}"
    POSTGRESQL | "tomcat" | "CALL dummy(?)"
    MYSQL      | "tomcat" | "{CALL dummy(?)}"
    SQLSERVER  | "tomcat" | "{CALL dummy(?)}"
    POSTGRESQL | "c3p0"   | "CALL dummy(?)"
    MYSQL      | "c3p0"   | "CALL dummy(?)"
    SQLSERVER  | "c3p0"   | "{CALL dummy(?)}"
    POSTGRESQL | null     | "CALL dummy(?)"
    MYSQL      | null     | "CALL dummy(?)"
    SQLSERVER  | null     | "{CALL dummy(?)}"
  }

  Driver driverFor(String db) {
    return newDriver(jdbcDriverClassNames.get(db))
  }

  Connection connectTo(String db, Properties properties) {
    return connect(jdbcDriverClassNames.get(db), jdbcUrls.get(db), properties)
  }

  Connection setupConnection(String pool, String db) {
    def conn =  pool ? cpDatasources.get(pool).get(db).getConnection() : connectTo(db, peerConnectionProps(db))

    // Clear any traces that pool or db can emmit on connection creation.
    TEST_WRITER.clear()

    return conn
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

  protected abstract boolean dbmTracePreparedStatements(String dbType)
}

class RemoteJDBCInstrumentationV0Test extends RemoteJDBCInstrumentationTest {

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

  @Override
  protected boolean dbmTracePreparedStatements(String dbType) {
    return false
  }
}

class RemoteJDBCInstrumentationV1ForkedTest extends RemoteJDBCInstrumentationTest {

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

  @Override
  protected boolean dbmTracePreparedStatements(String dbType) {
    return false
  }

  @Override
  protected String getDbType(String dbType) {
    final databaseNaming = new DatabaseNamingV1()
    return databaseNaming.normalizedName(dbType)
  }
}

class RemoteDBMTraceInjectedForkedTest extends RemoteJDBCInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.dbm.propagation.mode", "full")
  }

  @Override
  protected boolean dbmTraceInjected() {
    return true
  }

  @Override
  protected boolean dbmTracePreparedStatements(String dbType){
    return dbType == ORACLE
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
  protected String getDbType(String dbType) {
    final databaseNaming = new DatabaseNamingV1()
    return databaseNaming.normalizedName(dbType)
  }

  def "Oracle DBM comment contains instance name in dddbs and dddb, not generic type string"() {
    setup:
    // Use a query text unlikely to already be in v$sql cursor cache
    def markerQuery = "SELECT 1729 /* oracle-dbm-fix-verify */ FROM dual"
    def conn = connectTo(ORACLE, peerConnectionProps(ORACLE))

    when:
    def stmt = conn.createStatement()
    runUnderTrace("parent") {
      stmt.execute(markerQuery)
    }
    TEST_WRITER.waitForTraces(1)

    then:
    // Connect as system to read v$sql — system shares the same password as the test user
    // in the gvenzl/oracle-free image (both are set via ORACLE_PASSWORD).
    def adminUrl = "jdbc:oracle:thin:@//${oracle.getHost()}:${oracle.getMappedPort(1521)}/freepdb1"
    def adminConn = java.sql.DriverManager.getConnection(adminUrl, "system", oracle.getPassword())
    def rs = adminConn.createStatement().executeQuery(
      "SELECT sql_fulltext FROM v\$sql " +
      "WHERE sql_fulltext LIKE '%1729%' AND sql_fulltext LIKE '%dddbs%' " +
      "AND sql_fulltext LIKE '%oracle-dbm-fix-verify%' " +
      "AND ROWNUM = 1"
      )
    assert rs.next() : "Instrumented Oracle query not found in v\$sql — DBM comment may be missing"
    def sqlText = rs.getString(1)
    // dddbs and dddb should both carry the PDB/service name, not the generic "oracle" type string
    assert sqlText.contains("dddbs='freepdb1'") : "Expected dddbs='freepdb1' in SQL comment, got: ${sqlText}"
    assert sqlText.contains("dddb='freepdb1'")  : "Expected dddb='freepdb1' in SQL comment, got: ${sqlText}"
    assert !sqlText.contains("dddbs='oracle'")  : "dddbs must not be the generic type string 'oracle': ${sqlText}"

    cleanup:
    adminConn?.close()
    stmt?.close()
    conn?.close()
  }
}

class RemoteDBMTraceInjectedForkedTestTracePreparedStatements extends RemoteJDBCInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.dbm.propagation.mode", "full")
    injectSysConfig(DB_DBM_TRACE_PREPARED_STATEMENTS, "true")
  }

  @Override
  protected boolean dbmTraceInjected() {
    return true
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
  protected String getDbType(String dbType) {
    final databaseNaming = new DatabaseNamingV1()
    return databaseNaming.normalizedName(dbType)
  }

  @Override
  protected boolean dbmTracePreparedStatements(String dbType){
    return dbType == POSTGRESQL || dbType == ORACLE
  }
}
