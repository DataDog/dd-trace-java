import com.mchange.v2.c3p0.ComboPooledDataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import javax.sql.DataSource

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ScalikeJDBCInstrumentationTest extends InstrumentationSpecification {
  @Shared
  def dbName = "scalikejdbcUnitTest"

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
    ds.setPassword("")
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
    config.setPassword("")
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
    ds.setPassword("")
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

    injectSysConfig("dd.integration.jdbc-datasource.enabled", "true")
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

  def "scalikejdbc query test"() {
    setup:
    runUnderTrace("parent") {
      new ScalikeSqlExecutor().execute(jdbcDriverClassNames.get(driver), jdbcUrls.get(driver), username, null, query)
    }

    expect:
    assertTraces(1) {
      trace(3) {
        basicSpan(it, "parent")
        span {
          operationName "${driver}.query"
          serviceName driver
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" driver
            "$Tags.DB_INSTANCE" dbName.toLowerCase()
            if (username != null) {
              "$Tags.DB_USER" username
            }
            "$Tags.DB_OPERATION" "SELECT"
            peerServiceFrom(Tags.DB_INSTANCE)
            defaultTags()
          }
        }
        span {
          operationName "database.connection"
          serviceName span(2).serviceName
          resourceName "PoolingDataSource.getConnection"
          spanType null
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "java-jdbc-connection"
            defaultTagsNoPeerService()
          }
        }
      }
    }

    where:
    driver  | username | query                            | obfuscatedQuery
    "h2"    | null     | "SELECT 3"                       | "SELECT ?"
    "derby" | "APP"    | "SELECT 3 FROM SYSIBM.SYSDUMMY1" | "SELECT ? FROM SYSIBM.SYSDUMMY1"
  }
}
