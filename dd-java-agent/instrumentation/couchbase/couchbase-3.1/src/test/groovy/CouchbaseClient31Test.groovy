import com.couchbase.client.core.env.TimeoutConfig
import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.java.Bucket
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.ClusterOptions
import com.couchbase.client.java.env.ClusterEnvironment
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import java.time.Duration
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer
import spock.lang.Shared

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
    cluster.queryIndexes().createIndex(BUCKET, "test-index", Arrays.asList("something", "or_other"))
  }

  def cleanupSpec() {
    cluster.disconnect()
    couchbase.stop()
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
        ], span(0))
      }
    }
  }

  def "check query spans"() {
    when:
    cluster.query('select * from `test-bucket` limit 1')

    then:
    assertTraces(1) {
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
        ], span(0))
      }
    }
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, DDSpan parentSpan) {
    assertCouchbaseCall(trace, name, extraTags, null, parentSpan)
  }

  void assertCouchbaseCall(TraceAssert trace, String name, Map<String, Serializable> extraTags, String latestResource = null, DDSpan parentSpan = null) {
    if (isLatestDepTest) {
      if (latestResource != null) {
        name = latestResource
      } else {
        name = name.substring("cb.".length())
      }
    }
    def opName = parentSpan != null ? 'couchbase.internal' : 'couchbase.call'
    def isMeasured = parentSpan == null
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
