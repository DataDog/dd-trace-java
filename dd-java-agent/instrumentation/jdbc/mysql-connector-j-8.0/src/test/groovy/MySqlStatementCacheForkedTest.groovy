import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXPERIMENTAL_MYSQL_STMT_CACHE_ENABLED
import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.cachePrepStmts
import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.cachedStatementCountOrMinusOne
import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.prepStmtCacheSize
import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.prepStmtCacheSqlLimit
import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.useServerPrepStmts

import com.mysql.cj.jdbc.MysqlDataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import datadog.trace.instrumentation.jdbc.mysql.StatementCorpus
import java.sql.Connection

class MySqlStatementCacheForkedTest extends MySqlContainerSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TRACE_EXPERIMENTAL_MYSQL_STMT_CACHE_ENABLED, "true")
  }

  def "DriverManager connection gets the cache without touching the caller's Properties"() {
    setup:
    def props = credentials()
    def before = new Properties()
    before.putAll(props)

    when:
    def conn = connect(url(), props)

    then:
    cachePrepStmts(conn)
    prepStmtCacheSize(conn) == 250
    prepStmtCacheSqlLimit(conn) == 2048
    !useServerPrepStmts(conn)
    props == before

    cleanup:
    conn?.close()
  }

  def "HikariCP with jdbcUrl gets the cache"() {
    setup:
    def config = new HikariConfig()
    config.jdbcUrl = url()
    config.username = "corpus"
    config.password = "corpus"
    config.maximumPoolSize = 2
    config.addDataSourceProperty("connectTimeout", "5000")
    def ds = new HikariDataSource(config)
    def dsPropsBefore = new Properties()
    dsPropsBefore.putAll(config.dataSourceProperties)

    when:
    def conn = ds.connection

    then:
    cachePrepStmts(conn)
    !useServerPrepStmts(conn)
    config.dataSourceProperties == dsPropsBefore

    cleanup:
    conn?.close()
    ds?.close()
  }

  def "HikariCP with MysqlDataSource gets the cache"() {
    setup:
    def config = new HikariConfig()
    config.dataSourceClassName = MysqlDataSource.name
    config.addDataSourceProperty("url", url())
    config.addDataSourceProperty("user", "corpus")
    config.addDataSourceProperty("password", "corpus")
    config.maximumPoolSize = 2
    def ds = new HikariDataSource(config)

    when:
    def conn = ds.connection

    then:
    cachePrepStmts(conn)
    !useServerPrepStmts(conn)

    cleanup:
    conn?.close()
    ds?.close()
  }

  def "MysqlDataSource gets the cache unless set on the data source"() {
    setup:
    def ds = new MysqlDataSource()
    ds.url = url()
    ds.user = "corpus"
    ds.password = "corpus"
    if (explicit != null) {
      ds.cachePrepStmts = explicit
    }

    when:
    def conn = ds.connection

    then:
    cachePrepStmts(conn) == expected

    cleanup:
    conn?.close()

    where:
    explicit | expected
    null     | true
    false    | false
    true     | true
  }

  def "explicit settings win: #scenario"() {
    when:
    def props = credentials()
    props.putAll(extraProps)
    def conn = connect(url(urlParam), props)

    then:
    cachePrepStmts(conn) == cache
    prepStmtCacheSize(conn) == size
    useServerPrepStmts(conn) == serverPrep

    cleanup:
    conn?.close()

    where:
    scenario                        | urlParam                    | extraProps                    | cache | size | serverPrep
    "cache off in URL"              | "cachePrepStmts=false"      | [:]                           | false | 25   | false
    "cache off in Properties"       | null                        | [cachePrepStmts: "false"]     | false | 25   | false
    "cache off, odd case"           | "CachePrepStmts=false"      | [:]                           | false | 25   | false
    "size in URL kept"              | "prepStmtCacheSize=50"      | [:]                           | true  | 50   | false
    "size in Properties kept"       | null                        | [prepStmtCacheSize: "60"]     | true  | 60   | false
    "server statements left alone"  | "useServerPrepStmts=true"   | [:]                           | false | 25   | true
  }

  def "statement corpus gives identical results with the cache off and on"() {
    setup:
    def offCounts = []
    def onCounts = []
    def connector = { String explicit, String extra ->
      connect(url(explicit, extra))
    }

    when:
    def off = StatementCorpus.run({ String extra -> connector("cachePrepStmts=false", extra) } as StatementCorpus.Connector,
    { Connection c -> offCounts << cachePrepStmts(c) << cachedStatementCountOrMinusOne(c) } as StatementCorpus.ConnectionInspector)
    def on = StatementCorpus.run({ String extra -> connector(null, extra) } as StatementCorpus.Connector,
    { Connection c -> onCounts << cachePrepStmts(c) << cachedStatementCountOrMinusOne(c) } as StatementCorpus.ConnectionInspector)

    def dir = new File("build/stmt-cache-corpus")
    dir.mkdirs()
    new File(dir, "off.txt").text = off.join("\n")
    new File(dir, "on.txt").text = on.join("\n")

    then: "the cache really was off for one run and on (and used) for the other"
    offCounts == [false, -1]
    onCounts[0] == true
    onCounts[1] > 0

    and: "the corpus is not trivial"
    off.size() > 500
    off.any { it.startsWith("error ") }
    off.any { it.startsWith("long statement length ") && (it.split(" ")[3] as int) > 2048 }

    and:
    on == off
  }
}
