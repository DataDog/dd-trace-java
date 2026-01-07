import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import com.couchbase.client.core.cnc.RequestSpan
import com.couchbase.client.core.cnc.RequestTracer
import com.couchbase.client.core.env.TimeoutConfig
import com.couchbase.client.core.error.CouchbaseException
import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.core.error.ParsingFailureException
import com.couchbase.client.core.msg.RequestContext
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer
import reactor.core.publisher.Mono
import spock.lang.Shared

import java.time.Duration

abstract class CouchbaseClient32Test extends VersionedNamingTestBase {
  static final String BUCKET = 'test-bucket'
  static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseClient32Test)

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
    JsonObject  data = JsonObject.create()
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
        assertCouchbaseCall(it, "get", [
          'db.couchbase.collection' : '_default',
          'db.couchbase.document_id': { String },
          'db.couchbase.retries'    : { Long },
          'db.couchbase.scope'      : '_default',
          'db.couchbase.service'    : 'kv',
          'db.name'                 : BUCKET,
          'db.operation'            : 'get'
        ])
        assertCouchbaseDispatchCall(it, span(0), [
          'db.couchbase.collection'     : '_default',
          'db.couchbase.document_id'    : { String },
          'db.couchbase.scope'          : '_default',
          'db.name'                     : BUCKET
        ])
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
    ex != null
    assertTraces(1) {
      sortSpansByStart()
      trace(internalEnabled ? 2: 1) {
        assertCouchbaseCall(it, "get", [
          'db.couchbase.collection' : '_default',
          'db.couchbase.document_id': { String },
          'db.couchbase.retries'    : { Long },
          'db.couchbase.scope'      : '_default',
          'db.couchbase.service'    : 'kv',
          'db.name'                 : BUCKET,
          'db.operation'            : 'get'
        ], false, ex)
        if (internalEnabled) {
          assertCouchbaseDispatchCall(it, span(0), [
            'db.couchbase.collection'     : '_default',
            'db.couchbase.document_id'    : { String },
            'db.couchbase.scope'          : '_default',
            'db.name'                     : BUCKET
          ])
        }
      }
    }
    where:
    internalEnabled << [true, false]
  }

  def "check query spans"() {
    when:
    cluster.query('select * from `test-bucket` limit 1')

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        assertCouchbaseCall(it, 'select * from `test-bucket` limit ?', [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ])
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
      cluster.query(query)
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, 'query.parent')
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ], span(0))
        assertCouchbaseDispatchCall(it, span(1))
      }
    }
  }

  def "check async query spans with parent and adhoc #adhoc"() {
    setup:
    def query = 'select count(1) from `test-bucket` where (`something` = "else") limit 1'
    def normalizedQuery = 'select count(?) from `test-bucket` where (`something` = "else") limit ?'
    int count = 0

    when:
    runUnderTrace('async.parent') {
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
        basicSpan(it, 'async.parent')
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ], span(0))
        if (!adhoc) {
          assertCouchbaseCall(it, "PREPARE $normalizedQuery", [
            'db.couchbase.retries': { Long },
            'db.couchbase.service': 'query'
          ], span(1), true)
        }
        assertCouchbaseDispatchCall(it, span(adhoc ? 1 : 2))
      }
    }

    where:
    adhoc << [true, false]
  }

  def "check multiple async query spans with parent and adhoc false and internal spans enabled = #internalEnabled"() {
    setup:
    injectSysConfig("trace.couchbase.internal-spans.enabled", "$internalEnabled")
    def query = "select count(1) from `test-bucket` where (`something` = \"$queryArg\") limit 1"
    def normalizedQuery = "select count(?) from `test-bucket` where (`something` = \"$queryArg\") limit ?"
    int count1 = 0
    int count2 = 0
    def extraPrepare = isLatestDepTest

    when:
    runUnderTrace('async.multiple') {
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
      trace(internalEnabled ? (extraPrepare ? 8 : 7) : 3) {
        sortSpansByStart()
        basicSpan(it, 'async.multiple')
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ], span(0))
        if (internalEnabled) {
          assertCouchbaseCall(it, "PREPARE $normalizedQuery", [
            'db.couchbase.retries': { Long },
            'db.couchbase.service': 'query'
          ], span(1), true)
          assertCouchbaseDispatchCall(it, span(2))
        }
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ], span(0))
        if (internalEnabled) {
          if (extraPrepare) {
            assertCouchbaseCall(it, "PREPARE $normalizedQuery", [
              'db.couchbase.retries': { Long },
              'db.couchbase.service': 'query'
            ], span(4), true)
          }
          assertCouchbaseCall(it, normalizedQuery, [
            'db.couchbase.retries': { Long },
            'db.couchbase.service': 'query'
          ], span(4), true)
          assertCouchbaseDispatchCall(it, span(extraPrepare ? 6 : 5))
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
    def normalizedQuery = "select * from `test-bucket` limeit ?"
    Throwable ex = null

    when:
    runUnderTrace('query.failure') {
      try {
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
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
          'db.system'              : 'couchbase',
        ], span(0), false, ex)
        assertCouchbaseDispatchCall(it, span(1))
      }
    }
  }

  def "check multiple async error query spans with parent and adhoc false"() {
    setup:
    def query = 'select count(1) from `test-bucket` where (`something` = "wonderful") limeit 1'
    def normalizedQuery = 'select count(?) from `test-bucket` where (`something` = "wonderful") limeit ?'
    int count1 = 0
    int count2 = 0
    Throwable ex1 = null
    Throwable ex2 = null

    when:
    runUnderTrace('async.failure') {
      // This results in a call to AsyncCluster.query(...)
      try {
        cluster.query(query, QueryOptions.queryOptions().adhoc(false)).each {
          it.rowsAsObject().each {
            count1 = it.getInt('$1')
          }
        }
      } catch (CouchbaseException expected) {
        ex1 = expected
      }
      try {
        cluster.query(query, QueryOptions.queryOptions().adhoc(false)).each {
          it.rowsAsObject().each {
            count2 = it.getInt('$1')
          }
        }
      } catch (CouchbaseException expected) {
        ex2 = expected
      }
    }

    then:
    count1 == 0
    count2 == 0
    ex1 != null
    ex2 != null
    assertTraces(1) {
      sortSpansByStart()
      trace(7) {
        basicSpan(it, 'async.failure')
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ], span(0), false, ex1)
        assertCouchbaseCall(it, "PREPARE $normalizedQuery", [
          'db.couchbase.retries': { Long },
          'db.couchbase.service': 'query'
        ], span(1), true, ex1)
        assertCouchbaseDispatchCall(it, span(2))
        assertCouchbaseCall(it, normalizedQuery, [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query'
        ], span(0), false, ex2)
        assertCouchbaseCall(it, "PREPARE $normalizedQuery", [
          'db.couchbase.retries': { Long },
          'db.couchbase.service': 'query'
        ], span(4), true, ex2)
        assertCouchbaseDispatchCall(it, span(5))
      }
    }
  }

  def "check basic spans with custom request tracer"() {
    setup:
    def customTracer = new TestRequestTracer()

    ClusterEnvironment environmentWithCustomTracer = ClusterEnvironment.builder()
      .timeoutConfig(TimeoutConfig.kvTimeout(Duration.ofSeconds(10)))
      .requestTracer(customTracer)
      .build()

    def connectionString = "couchbase://${couchbase.host}:${couchbase.bootstrapCarrierDirectPort},${couchbase.host}:${couchbase.bootstrapHttpDirectPort}=manager"

    Cluster localCluster = Cluster.connect(
      connectionString,
      ClusterOptions
      .clusterOptions(couchbase.username, couchbase.password)
      .environment(environmentWithCustomTracer)
      )
    Bucket localBucket = localCluster.bucket(BUCKET)
    localBucket.waitUntilReady(Duration.ofSeconds(30))
    def collection = localBucket.defaultCollection()

    when:
    collection.get("data 0")

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        assertCouchbaseCall(it, "get", [
          'db.couchbase.collection' : '_default',
          'db.couchbase.document_id': { String },
          'db.couchbase.retries'    : { Long },
          'db.couchbase.scope'      : '_default',
          'db.couchbase.service'    : 'kv',
          'db.name'                 : BUCKET,
          'db.operation'            : 'get'
        ])
        assertCouchbaseDispatchCall(it, span(0), [
          'db.couchbase.collection'     : '_default',
          'db.couchbase.document_id'    : { String },
          'db.couchbase.scope'          : '_default',
          'db.name'                     : BUCKET
        ])
      }
    }

    and: "custom tracer also saw spans"
    customTracer.spans.size() > 0
    customTracer.spans*.ended.every { it == true }

    cleanup:
    try {
      localCluster?.disconnect()
    } catch (Throwable t) {
      LOGGER.debug("Unable to properly disconnect localCluster in custom tracer test", t)
    }
    try {
      environmentWithCustomTracer?.shutdown()
    } catch (Throwable t) {
      LOGGER.debug("Unable to properly shutdown environmentWithCustomTracer", t)
    }
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, boolean internal = false, Throwable ex = null) {
    assertCouchbaseCall(trace, name, extraTags, null, internal, ex)
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, Object parentSpan, boolean internal = false, Throwable ex = null) {
    def opName = internal ? 'couchbase.internal' : operation()
    def isMeasured = !internal
    def isErrored = ex != null
    // Later versions of the couchbase client adds more information at the end of the exception message in some cases,
    // so let's just match on the start of the message when that happens
    String exMessage = isErrored ? isLatestDepTest ? ex.message.split("\\{")[0] : ex.message: null
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
        'db.system' 'couchbase'
        "$InstrumentationTags.COUCHBASE_SEED_NODES" { it =="localhost" || it == "127.0.0.1" || it == couchbase.getHost() }
        if (isErrored) {
          it.tag(DDTags.ERROR_MSG, { exMessage.length() > 0 && ((String) it).startsWith(exMessage) })
          it.tag(DDTags.ERROR_TYPE, ex.class.name)
          it.tag(DDTags.ERROR_STACK, String)
        }
        if (extraTags != null) {
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
    assertCouchbaseCall(trace, 'dispatch_to_server', allExtraTags, parentSpan, true, null)
  }

  static class TestRequestTracer implements RequestTracer {

    final List<TestRequestSpan> spans = new CopyOnWriteArrayList<>()

    @Override
    RequestSpan requestSpan(String requestName, RequestSpan parent) {
      def span = new TestRequestSpan(requestName, parent)
      spans.add(span)
      return span
    }

    @Override
    Mono<Void> start() {
      return Mono.empty()
    }

    @Override
    Mono<Void> stop(Duration timeout) {
      return Mono.empty()
    }
  }

  static class TestRequestSpan implements RequestSpan {

    final String name
    final RequestSpan parent
    final Map<String, Object> attributes = new LinkedHashMap<>()
    final List<String> events = []
    volatile boolean ended = false

    TestRequestSpan(String name, RequestSpan parent) {
      this.name = name
      this.parent = parent
    }

    @Override
    void end() {
      ended = true
    }

    @Override
    void attribute(String key, String value) {
      attributes.put(key, value)
    }

    @Override
    void attribute(String key, boolean value) {
      attributes.put(key, value)
    }

    @Override
    void attribute(String key, long value) {
      attributes.put(key, value)
    }

    @Override
    void event(String name, Instant timestamp) {
      events.add(name)
    }

    @Override
    void status(StatusCode status) {
    }

    @Override
    void requestContext(RequestContext requestContext) {
    }
  }
}

class CouchbaseClient32V0Test extends CouchbaseClient32Test {
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

class CouchbaseClient32V1ForkedTest extends CouchbaseClient32Test {
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
