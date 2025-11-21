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
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Requires
import spock.lang.Shared

import javax.sql.DataSource
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Duration
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_TRACE_PREPARED_STATEMENTS

// workaround for SSLHandShakeException on J9 only with Hikari/MySQL
@Requires({
  !System.getProperty("java.vendor").contains("IBM")
})
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
    postgres = new PostgreSQLContainer("postgres:11.2")
      .withDatabaseName(dbName.get(POSTGRESQL)).withUsername(jdbcUserNames.get(POSTGRESQL)).withPassword(jdbcPasswords.get(POSTGRESQL))
    postgres.start()
    PortUtils.waitForPortToOpen(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put(POSTGRESQL, "${postgres.getJdbcUrl()}")
    mysql = new MySQLContainer("mysql:8.0")
      .withDatabaseName(dbName.get(MYSQL)).withUsername(jdbcUserNames.get(MYSQL)).withPassword(jdbcPasswords.get(MYSQL))
    // https://github.com/testcontainers/testcontainers-java/issues/914
    mysql.addParameter("TC_MY_CNF", null)
    mysql.start()
    PortUtils.waitForPortToOpen(mysql.getHost(), mysql.getMappedPort(MySQLContainer.MYSQL_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put(MYSQL, "${mysql.getJdbcUrl()}")
    sqlserver = new MSSQLServerContainer(MSSQLServerContainer.IMAGE).acceptLicense().withPassword(jdbcPasswords.get(SQLSERVER))
    sqlserver.start()
    PortUtils.waitForPortToOpen(sqlserver.getHost(), sqlserver.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT), 5, TimeUnit.SECONDS)
    jdbcUrls.put(SQLSERVER, "${sqlserver.getJdbcUrl()};DatabaseName=${dbName.get(SQLSERVER)}")

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
  }

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
    driver     | connection                                              | renameService | query                   | operation | obfuscatedQuery         | usingHikari
    MYSQL      | connectTo(driver, peerConnectionProps(driver))          | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | connectTo(driver, peerConnectionProps(driver))          | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | false
    SQLSERVER  | connectTo(driver, peerConnectionProps(driver))          | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | connectTo(driver, peerConnectionProps(driver))          | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | false
    SQLSERVER  | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | cpDatasources.get("tomcat").get(driver).getConnection() | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    POSTGRESQL | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | true
    SQLSERVER  | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    ORACLE     | cpDatasources.get("hikari").get(driver).getConnection() | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | true
    MYSQL      | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3 FROM pg_user" | "SELECT"  | "SELECT ? FROM pg_user" | false
    SQLSERVER  | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | cpDatasources.get("c3p0").get(driver).getConnection()   | false         | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
  }

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
    driver     | connection                                              | query                   | operation | obfuscatedQuery         | usingHikari
    MYSQL      | connectTo(driver, peerConnectionProps(driver))          | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | connectTo(driver, peerConnectionProps(driver))          | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | false
    SQLSERVER  | connectTo(driver, peerConnectionProps(driver))          | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE  | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | false
    SQLSERVER  | cpDatasources.get("tomcat").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE  | cpDatasources.get("tomcat").get(driver).getConnection()    | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
    MYSQL      | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    POSTGRESQL | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | true
    SQLSERVER  | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"              | true
    ORACLE     | cpDatasources.get("hikari").get(driver).getConnection() | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | true
    MYSQL      | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    POSTGRESQL | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user" | false
    SQLSERVER  | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3"              | "SELECT"  | "SELECT ?"              | false
    ORACLE     | cpDatasources.get("c3p0").get(driver).getConnection()   | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"    | false
  }

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
              if (conPoolType == "hikari") {
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
    driver     | conPoolType | connection                                                 | query                   | operation | obfuscatedQuery
    MYSQL      | ""          | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | ""          | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | ""          | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | ""          | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
    MYSQL      | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
    MYSQL      | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
    MYSQL      | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 FROM dual"    | "SELECT"  | "SELECT ? FROM dual"
  }

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
              if (conPoolType == "hikari") {
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
    driver     | conPoolType  | connection                                                 | query                   | operation | obfuscatedQuery
    MYSQL      | ""           | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | ""           | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | ""           | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | ""           | connectTo(driver, peerConnectionProps(driver))             | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
    MYSQL      | "tomcat"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "tomcat"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "tomcat"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "tomcat"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
    MYSQL      | "hikari"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "hikari"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "hikari"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "hikari"     | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
    MYSQL      | "c3p0"       | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    POSTGRESQL | "c3p0"       | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from pg_user" | "SELECT"  | "SELECT ? from pg_user"
    SQLSERVER  | "c3p0"       | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3"              | "SELECT"  | "SELECT ?"
    ORACLE     | "c3p0"       | cpDatasources.get(conPoolType).get(driver).getConnection() | "SELECT 3 from DUAL"    | "SELECT"  | "SELECT ? from DUAL"
  }

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
    driver     | conPoolType | connection                                                 | query                                                                            | operation
    MYSQL      | ""          | connectTo(driver, peerConnectionProps(driver))             | "CREATE TEMPORARY TABLE s_test_ (id INTEGER not NULL, PRIMARY KEY ( id ))"       | "CREATE"
    POSTGRESQL | ""          | connectTo(driver, peerConnectionProps(driver))             | "CREATE TEMPORARY TABLE s_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    SQLSERVER  | ""          | connectTo(driver, peerConnectionProps(driver))             | "CREATE TABLE #s_test_ (id INTEGER not NULL, PRIMARY KEY ( id ))"                | "CREATE"
    ORACLE     | ""          | connectTo(driver, peerConnectionProps(driver))             | "CREATE GLOBAL TEMPORARY TABLE s_test (id INTEGER not NULL, PRIMARY KEY ( id ))"        | "CREATE"
    MYSQL      | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    POSTGRESQL | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    SQLSERVER  | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE #s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))"          | "CREATE"
    ORACLE     | "tomcat"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE GLOBAL TEMPORARY TABLE s_tomcat_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    MYSQL      | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    POSTGRESQL | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    SQLSERVER  | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE #s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))"          | "CREATE"
    ORACLE     | "hikari"    | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE GLOBAL TEMPORARY TABLE s_hikari_test (id INTEGER not NULL, PRIMARY KEY ( id ))" | "CREATE"
    MYSQL      | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
    POSTGRESQL | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
    SQLSERVER  | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE TABLE #s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"            | "CREATE"
    ORACLE     | "c3p0"      | cpDatasources.get(conPoolType).get(driver).getConnection() | "CREATE GLOBAL TEMPORARY TABLE s_c3p0_test (id INTEGER not NULL, PRIMARY KEY ( id ))"   | "CREATE"
  }


  def "prepared procedure call with return value on #driver with #connection.getClass().getCanonicalName() does not hang"() {
    setup:
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
    driver     | connection                                              | query
    POSTGRESQL | cpDatasources.get("hikari").get(driver).getConnection() | "{ ? = call upper( ? ) }"
    MYSQL      | cpDatasources.get("hikari").get(driver).getConnection() | "{ ? = call upper( ? ) }"
    POSTGRESQL | cpDatasources.get("tomcat").get(driver).getConnection() | "{ ? = call upper( ? ) }"
    MYSQL      | cpDatasources.get("tomcat").get(driver).getConnection() | "{ ? = call upper( ? ) }"
    POSTGRESQL | cpDatasources.get("c3p0").get(driver).getConnection()   | "{ ? = call upper( ? ) }"
    MYSQL      | cpDatasources.get("c3p0").get(driver).getConnection()   | "{ ? = call upper( ? ) }"
    POSTGRESQL | connectTo(driver, peerConnectionProps(driver))          | "{ ? = call upper( ? ) }"
    MYSQL      | connectTo(driver, peerConnectionProps(driver))          | "{ ? = call upper( ? ) }"
  }

  def "prepared procedure call on #driver with #connection.getClass().getCanonicalName() does not hang"() {
    setup:

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
    driver       | connection                                            | query
    POSTGRESQL | cpDatasources.get("hikari").get(driver).getConnection() | "CALL dummy(?)"
    MYSQL      | cpDatasources.get("hikari").get(driver).getConnection() | "CALL dummy(?)"
    SQLSERVER  | cpDatasources.get("hikari").get(driver).getConnection() | "{CALL dummy(?)}"
    POSTGRESQL | cpDatasources.get("tomcat").get(driver).getConnection() | "CALL dummy(?)"
    MYSQL      | cpDatasources.get("tomcat").get(driver).getConnection() | "{CALL dummy(?)}"
    SQLSERVER  | cpDatasources.get("tomcat").get(driver).getConnection() | "{CALL dummy(?)}"
    POSTGRESQL | cpDatasources.get("c3p0").get(driver).getConnection()   | "CALL dummy(?)"
    MYSQL      | cpDatasources.get("c3p0").get(driver).getConnection()   | "CALL dummy(?)"
    SQLSERVER  | cpDatasources.get("c3p0").get(driver).getConnection()   | "{CALL dummy(?)}"
    POSTGRESQL | connectTo(driver, peerConnectionProps(driver))          | "CALL dummy(?)"
    MYSQL      | connectTo(driver, peerConnectionProps(driver))          | "CALL dummy(?)"
    SQLSERVER  | connectTo(driver, peerConnectionProps(driver))          | "{CALL dummy(?)}"
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
