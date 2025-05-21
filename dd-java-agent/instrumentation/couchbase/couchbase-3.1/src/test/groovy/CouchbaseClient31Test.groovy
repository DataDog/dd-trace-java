import com.couchbase.client.core.env.TimeoutConfig
import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.core.error.ParsingFailureException
import com.couchbase.client.java.Bucket
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.ClusterOptions
import com.couchbase.client.java.env.ClusterEnvironment
import com.couchbase.client.java.json.JsonObject
import com.couchbase.client.java.query.QueryOptions
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class CouchbaseClient31Test extends VersionedNamingTestBase {
  static final String BUCKET = 'test-bucket'

  static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseClient31Test)

  @Shared
  CouchbaseContainer couchbase
  @Shared
  Cluster cluster
  @Shared
  Bucket bucket

  def setupSpec() {
    def arch = System.getProperty("os.arch") == "aarch64" ? "-aarch64" : ""
    couchbase = new CouchbaseContainer("couchbase/server:7.1.0${arch}")
      .withBucket(new BucketDefinition(BUCKET).withPrimaryIndex(true))
      .withStartupTimeout(Duration.ofSeconds(240))
      .withStartupAttempts(3)
    couchbase.start()

    ClusterEnvironment environment = ClusterEnvironment.builder()
      .timeoutConfig(TimeoutConfig.kvTimeout(Duration.ofSeconds(10)))
      .build()

    def connectionString = "couchbase://${couchbase.host}:${couchbase.bootstrapCarrierDirectPort},${couchbase.host}:${couchbase.bootstrapHttpDirectPort}=manager"
    cluster = Cluster.connect(connectionString, ClusterOptions
      .clusterOptions(couchbase.username, couchbase.password)
      .environment(environment))
    bucket = cluster.bucket(BUCKET)
    bucket.waitUntilReady(Duration.ofSeconds(30))
    (0..1000).each {
      def type = ['data', 'tada', 'todo'].get(it % 3)
      def something = ['other', 'like', 'else', 'wonderful'].get(it % 4)
      def orOther = ['foo', 'bar'].get(it % 2)
      insertData(bucket, "$type $it", something, orOther)
    }
    cluster.queryIndexes().createIndex(BUCKET, 'test-index', Arrays.asList('something', 'or_other'))
  }

  def cleanupSpec() {
    try {
      cluster?.disconnect()
    } catch (Throwable t) {
      LOGGER.debug("Unable to properly disconnect on cleanup", t)
    }
    couchbase?.stop()
  }

  void insertData(Bucket bucket, String id, String something, String orOther) {
    JsonObject data = JsonObject.create()
      .put('something', something)
      .put('or_other', orOther)

    bucket.defaultCollection().upsert(id, data)
  }

  def "check basic spans"() {
    setup:
    def collection = bucket.defaultCollection()

    when:
    collection.get("data 0")

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        assertCouchbaseCall(it, "cb.get", [
          'db.couchbase.collection': '_default',
          'db.couchbase.retries'   : { Long },
          'db.couchbase.scope'     : '_default',
          'db.couchbase.service'   : 'kv',
          'db.name'                : BUCKET,
          'db.operation'           : 'get',
        ])
        assertCouchbaseDispatchCall(it, span(0))
      }
    }
  }

  def "check basic error spans with internal spans enabled #internalEnabled"() {
    setup:
    injectSysConfig("trace.couchbase.internal-spans.enabled", "$internalEnabled")
    def collection = bucket.defaultCollection()
    Throwable ex = null

    when:
    try {
      collection.get("not_found")
    } catch (DocumentNotFoundException expected) {
      ex = expected
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(internalEnabled ? 2 : 1) {
        assertCouchbaseCall(it, "cb.get", [
          'db.couchbase.collection': '_default',
          'db.couchbase.retries'   : { Long },
          'db.couchbase.scope'     : '_default',
          'db.couchbase.service'   : 'kv',
          'db.name'                : BUCKET,
          'db.operation'           : 'get',
        ], false, ex)
        if (internalEnabled) {
          assertCouchbaseDispatchCall(it, span(0))
        }
      }
    }
    where:
    internalEnabled | _
    true            | _
    false           | _
  }

  def "check query spans"() {
    when:
    // These queries always use the underlying async queries
    cluster.query('select * from `test-bucket` limit 1')

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
        ], 'select * from `test-bucket` limit ?')
        assertCouchbaseDispatchCall(it, span(0))
      }
    }
  }

  def "check query spans with parent"() {
    setup:
    def query = 'select * from `test-bucket` limit 1'
    def normalizedQuery = 'select * from `test-bucket` limit ?'

    when:
    runUnderTrace('query.parent') {
      // These queries always use the underlying async queries
      cluster.query(query)
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, 'query.parent')
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
        ], normalizedQuery, span(0), false)
        assertCouchbaseDispatchCall(it, span(1))
      }
    }
  }

  def "check query spans with parent and adhoc #adhoc"() {
    def query = 'select count(1) from `test-bucket` where (`something` = "else") limit 1'
    def normalizedQuery = 'select count(?) from `test-bucket` where (`something` = "else") limit ?'
    int count = 0

    when:
    runUnderTrace('adhoc.parent') {
      // This results in a call to AsyncCluster.query(...)
      cluster.query(query, QueryOptions.queryOptions().adhoc(adhoc)).each {
        it.rowsAsObject().each {
          count = it.getInt('$1')
        }
      }
    }

    then:
    count == 250
    assertTraces(1) {
      sortSpansByStart()
      trace(adhoc ? 3 : 4) {
        basicSpan(it, 'adhoc.parent')
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
        ], normalizedQuery, span(0), false)
        if (!adhoc) {
          assertCouchbaseCall(it, "prepare", [
            'db.couchbase.retries'   : { Long },
            'db.couchbase.service'   : 'query',
          ], "PREPARE $normalizedQuery", span(1), true)
        }
        assertCouchbaseDispatchCall(it, span(adhoc ? 1 : 2))
      }
    }

    where:
    adhoc << [true, false]
  }

  def "check multiple query spans with parent and adhoc false and internal spans enabled = #internalEnabled"() {
    setup:
    injectSysConfig("trace.couchbase.internal-spans.enabled", "$internalEnabled")
    def query = "select count(1) from `test-bucket` where (`something` = \"$queryArg\") limit 1"
    def normalizedQuery = "select count(?) from `test-bucket` where (`something` = \"$queryArg\") limit ?"
    int count1 = 0
    int count2 = 0

    when:
    runUnderTrace('multiple.parent') {
      // This results in a call to AsyncCluster.query(...)
      cluster.query(query, QueryOptions.queryOptions().adhoc(false)).each {
        it.rowsAsObject().each {
          count1 = it.getInt('$1')
        }
      }
      cluster.query(query, QueryOptions.queryOptions().adhoc(false)).each {
        it.rowsAsObject().each {
          count2 = it.getInt('$1')
        }
      }
    }

    then:
    count1 == expectedCount
    count2 == expectedCount
    assertTraces(1) {
      sortSpansByStart()
      trace(internalEnabled ? 7 : 3) {
        basicSpan(it, 'multiple.parent')
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
        ], normalizedQuery, span(0), false)
        if (internalEnabled) {
          assertCouchbaseCall(it, "prepare", [
            'db.couchbase.retries': { Long },
            'db.couchbase.service': 'query',
          ], "PREPARE $normalizedQuery", span(1), true)
          assertCouchbaseDispatchCall(it, span(2))
        }
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
        ], normalizedQuery, span(0), false)
        if (internalEnabled) {
          assertCouchbaseCall(it, "execute", [
            'db.couchbase.retries': { Long },
            'db.couchbase.service': 'query',
          ], normalizedQuery, span(4), true)
          assertCouchbaseDispatchCall(it, span(5))
        }
      }
    }
    where:
    internalEnabled | queryArg      | expectedCount
    true            | "wonderful"   | 250
    false           | "notinternal" | 0             // avoid having the query engine reusing previous prepared query
  }

  def "check error query spans with parent"() {
    setup:
    def query = 'select * from `test-bucket` limeit 1'
    def normalizedQuery = 'select * from `test-bucket` limeit ?'
    Throwable ex = null

    when:
    runUnderTrace('query.failure') {
      try {
        // These queries always use the underlying async queries
        cluster.query(query)
      } catch (ParsingFailureException expected) {
        ex = expected
      }
    }

    then:
    ex != null
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, 'query.failure')
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
        ], normalizedQuery, span(0), false, ex)
        assertCouchbaseDispatchCall(it, span(1))
      }
    }
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, boolean internal = false, Throwable ex = null) {
    assertCouchbaseCall(trace, name, extraTags, null, null, internal, ex)
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, DDSpan parentSpan, boolean internal = false, Throwable ex = null) {
    assertCouchbaseCall(trace, name, extraTags, null, parentSpan, internal, ex)
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, String latestResource, DDSpan parentSpan = null, boolean internal = false, Throwable ex = null) {
    def opName = internal ? 'couchbase.internal' : operation()
    def isMeasured = !internal
    def isErrored = ex != null
    // Later versions of the couchbase client adds more information at the end of the exception message in some cases,
    // so let's just match on the start of the message when that happens
    String exMessage = isErrored ? isLatestDepTest ? ex.message.split("\\{")[0] : ex.message: null
    if (isLatestDepTest) {
      if (latestResource != null) {
        name = latestResource
      } else {
        name = name.substring('cb.'.length())
      }
    }
    trace.span {
      serviceName service()
      resourceName name
      operationName opName
      spanType DDSpanTypes.COUCHBASE
      errored isErrored
      measured isMeasured
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      tags {
        "$Tags.COMPONENT" 'couchbase-client'
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" 'couchbase'
        if (isErrored) {
          it.tag(DDTags.ERROR_MSG, { exMessage.length() > 0 && ((String) it).startsWith(exMessage) })
          it.tag(DDTags.ERROR_TYPE, ex.class.name)
          it.tag(DDTags.ERROR_STACK, String)
        }
        "$InstrumentationTags.COUCHBASE_SEED_NODES" { it =="localhost" || it == "127.0.0.1" || it == couchbase.getHost() }

        if (isLatestDepTest && extraTags != null) {
          tag('db.system','couchbase')
          addTags(extraTags)
        }
        peerServiceFrom(InstrumentationTags.COUCHBASE_SEED_NODES)
        defaultTags()
      }
    }
  }

  void assertCouchbaseDispatchCall(TraceAssert trace, Object parentSpan, Map<String, Serializable> extraTags = null) {
    Map<String, Serializable> allExtraTags = [
      'db.couchbase.local_id'       : { String },
      'db.couchbase.operation_id'   : { String },
      'db.couchbase.server_duration': { Long },
      'net.host.name'               : { String },
      'net.host.port'               : { Long },
      'net.peer.name'               : { String },
      'net.peer.port'               : { Long },
      'net.transport'               : { String },
    ]
    if (extraTags != null) {
      allExtraTags.putAll(extraTags)
    }
    assertCouchbaseCall(trace, 'cb.dispatch_to_server', allExtraTags, (DDSpan) parentSpan, true)
  }
}

class CouchbaseClient31V0Test extends CouchbaseClient31Test {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "couchbase"
  }

  @Override
  String operation() {
    return "couchbase.call"
  }
}

class CouchbaseClient31V1ForkedTest extends CouchbaseClient31Test {
  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return Config.get().getServiceName()
  }

  @Override
  String operation() {
    return "couchbase.query"
  }
}
