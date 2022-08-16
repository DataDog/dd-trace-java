import com.couchbase.client.core.env.TimeoutConfig
import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.java.Bucket
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.ClusterOptions
import com.couchbase.client.java.env.ClusterEnvironment
import com.couchbase.client.java.json.JsonObject
import com.couchbase.client.java.query.QueryOptions
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import java.time.Duration
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CouchbaseClient31Test extends AgentTestRunner {
  static final String BUCKET = 'test-bucket'

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
      .withStartupTimeout(Duration.ofSeconds(120))
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
    (1..1001).each {
      def type = ['data', 'tada', 'todo'].get(it % 3)
      def something = ['other', 'like', 'else', 'wonderful'].get(it % 4)
      def orOther = ['foo', 'bar'].get(it % 2)
      insertData(bucket, "$type $it", something, orOther)
    }
    cluster.queryIndexes().createIndex(BUCKET, 'test-index', Arrays.asList('something', 'or_other'))
  }

  def cleanupSpec() {
    cluster.disconnect()
    couchbase.stop()
  }

  private static void insertData(Bucket bucket, String id, String something, String orOther) {
    JsonObject data = JsonObject.create()
      .put('something', something)
      .put('or_other', orOther)

    bucket.defaultCollection().upsert(id, data)
  }

  def "check basic spans"() {
    setup:
    def collection = bucket.defaultCollection()

    when:
    try {
      collection.get("not_found")
    } catch (DocumentNotFoundException ignored) {
      // expected exception
    }

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
          'db.system'              : 'couchbase',
        ])
        assertCouchbaseCall(it, "cb.dispatch_to_server", [
          'db.couchbase.local_id'       : { String },
          'db.couchbase.operation_id'   : { String },
          'db.couchbase.server_duration': { Long },
          'db.system'                   : 'couchbase',
          'net.host.name'               : { String },
          'net.host.port'               : { Long },
          'net.peer.name'               : { String },
          'net.peer.port'               : { Long },
          'net.transport'               : { String },
        ], span(0), true)
      }
    }
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
          'db.system'              : 'couchbase',
        ], 'select * from `test-bucket` limit 1')
        assertCouchbaseCall(it, "cb.dispatch_to_server", [
          'db.couchbase.local_id'       : { String },
          'db.couchbase.operation_id'   : { String },
          'db.couchbase.server_duration': { Long },
          'db.system'                   : 'couchbase',
          'net.host.name'               : { String },
          'net.host.port'               : { Long },
          'net.peer.name'               : { String },
          'net.peer.port'               : { Long },
          'net.transport'               : { String },
        ], span(0), true)
      }
    }
  }

  def "check query spans with parent"() {
    setup:
    def query = 'select * from `test-bucket` limit 1'

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
          'db.system'              : 'couchbase',
        ], query, span(0), false)
        assertCouchbaseCall(it, "cb.dispatch_to_server", [
          'db.couchbase.local_id'       : { String },
          'db.couchbase.operation_id'   : { String },
          'db.couchbase.server_duration': { Long },
          'db.system'                   : 'couchbase',
          'net.host.name'               : { String },
          'net.host.port'               : { Long },
          'net.peer.name'               : { String },
          'net.peer.port'               : { Long },
          'net.transport'               : { String },
        ], span(1), true)
      }
    }
  }

  def "check query spans with parent and adhoc #adhoc"() {
    def query = 'select count(1) from `test-bucket` where (`something` = "else") limit 1'
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
          'db.system'              : 'couchbase',
        ], query, span(0), false)
        if (!adhoc) {
          assertCouchbaseCall(it, "prepare", [
            'db.couchbase.retries'   : { Long },
            'db.couchbase.service'   : 'query',
            'db.system'              : 'couchbase',
          ], "PREPARE $query", span(1), true)
        }
        assertCouchbaseCall(it, "cb.dispatch_to_server", [
          'db.couchbase.local_id'       : { String },
          'db.couchbase.operation_id'   : { String },
          'db.couchbase.server_duration': { Long },
          'db.system'                   : 'couchbase',
          'net.host.name'               : { String },
          'net.host.port'               : { Long },
          'net.peer.name'               : { String },
          'net.peer.port'               : { Long },
          'net.transport'               : { String },
        ], span(adhoc ? 1 : 2), true)
      }
    }

    where:
    adhoc << [true, false]
  }

  def "check multiple query spans with parent and adhoc false"() {
    def query = 'select count(1) from `test-bucket` where (`something` = "wonderful") limit 1'
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
    count1 == 250
    count2 == 250
    assertTraces(1) {
      sortSpansByStart()
      trace(7) {
        basicSpan(it, 'multiple.parent')
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
          'db.system'              : 'couchbase',
        ], query, span(0), false)
        assertCouchbaseCall(it, "prepare", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
          'db.system'              : 'couchbase',
        ], "PREPARE $query", span(1), true)
        assertCouchbaseCall(it, "cb.dispatch_to_server", [
          'db.couchbase.local_id'       : { String },
          'db.couchbase.operation_id'   : { String },
          'db.couchbase.server_duration': { Long },
          'db.system'                   : 'couchbase',
          'net.host.name'               : { String },
          'net.host.port'               : { Long },
          'net.peer.name'               : { String },
          'net.peer.port'               : { Long },
          'net.transport'               : { String },
        ], span(2), true)
        assertCouchbaseCall(it, "cb.query", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
          'db.system'              : 'couchbase',
        ], query, span(0), false)
        assertCouchbaseCall(it, "execute", [
          'db.couchbase.retries'   : { Long },
          'db.couchbase.service'   : 'query',
          'db.system'              : 'couchbase',
        ], query, span(4), true)
        assertCouchbaseCall(it, "cb.dispatch_to_server", [
          'db.couchbase.local_id'       : { String },
          'db.couchbase.operation_id'   : { String },
          'db.couchbase.server_duration': { Long },
          'db.system'                   : 'couchbase',
          'net.host.name'               : { String },
          'net.host.port'               : { Long },
          'net.peer.name'               : { String },
          'net.peer.port'               : { Long },
          'net.transport'               : { String },
        ], span(5), true)
      }
    }
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, DDSpan parentSpan, boolean internal = false) {
    assertCouchbaseCall(trace, name, extraTags, null, parentSpan, internal)
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, String latestResource = null, DDSpan parentSpan = null, boolean internal = false) {
    def opName = internal ? 'couchbase.internal' : 'couchbase.call'
    def isMeasured = !internal
    if (isLatestDepTest) {
      if (latestResource != null) {
        name = latestResource
      } else {
        name = name.substring("cb.".length())
      }
    }
    trace.span {
      serviceName "couchbase"
      resourceName name
      operationName opName
      spanType DDSpanTypes.COUCHBASE
      errored false
      measured isMeasured
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      tags {
        "$Tags.COMPONENT" "couchbase-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "couchbase"
        if (isLatestDepTest && extraTags != null) {
          addTags(extraTags)
        }
        defaultTags()
      }
    }
  }
}
