import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import com.couchbase.client.java.AsyncCluster
import com.couchbase.client.java.CouchbaseAsyncCluster
import com.couchbase.client.java.document.JsonDocument
import com.couchbase.client.java.document.json.JsonObject
import com.couchbase.client.java.env.CouchbaseEnvironment
import com.couchbase.client.java.query.N1qlQuery
import spock.lang.Unroll
import spock.util.concurrent.BlockingVariable
import util.AbstractCouchbaseTest

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Unroll
class CouchbaseAsyncClientTest extends AbstractCouchbaseTest {
  static final int TIMEOUT = 30

  @Override
  boolean useStrictTraceWrites() {
    // Async spans often finish out of order, so allow buffering.
    return false
  }

  def "test hasBucket #type"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def hasBucket = new BlockingVariable<Boolean>(TIMEOUT)

    when:
    cluster.openBucket(bucketSettings.name(), bucketSettings.password()).subscribe({ bkt ->
      manager.hasBucket(bucketSettings.name()).subscribe({ result -> hasBucket.set(result) })
    })

    then:
    assert hasBucket.get()
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        assertCouchbaseCall(it, "Cluster.openBucket", null)
        assertCouchbaseCall(it, "ClusterManager.hasBucket", null, span(0))
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)

    where:
    bucketSettings << [bucketCouchbase, bucketMemcache]

    environment = envBuilder(bucketSettings).build()
    cluster = CouchbaseAsyncCluster.create(environment, Arrays.asList("127.0.0.1"))
    manager = cluster.clusterManager(USERNAME, PASSWORD).toBlocking().single()
    type = bucketSettings.type().name()

    waitForTraces = {
      TEST_WRITER.waitForTraces(1) // from clusterManager above
      true
    }()
  }

  def "test upsert #type"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    JsonObject content = JsonObject.create().put("hello", "world")
    def inserted = new BlockingVariable<JsonDocument>(TIMEOUT)

    when:
    runUnderTrace("someTrace") {
      // Connect to the bucket and open it
      cluster.openBucket(bucketSettings.name(), bucketSettings.password()).subscribe({ bkt ->
        bkt.upsert(JsonDocument.create("helloworld", content)).subscribe({ result -> inserted.set(result) })
      })
    }

    then:
    inserted.get().content().getString("hello") == "world"

    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Cluster.openBucket", null, span(0))
        assertCouchbaseCall(it, "Bucket.upsert", bucketSettings.name(), span(1))
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)

    where:
    bucketSettings << [bucketCouchbase, bucketMemcache]

    environment = envBuilder(bucketSettings).build()
    cluster = CouchbaseAsyncCluster.create(environment, Arrays.asList("127.0.0.1"))
    type = bucketSettings.type().name()
  }

  def "test upsert and get #type"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    JsonObject content = JsonObject.create().put("hello", "world")
    def inserted = new BlockingVariable<JsonDocument>(TIMEOUT)
    def found = new BlockingVariable<JsonDocument>(TIMEOUT)

    when:
    runUnderTrace("someTrace") {
      cluster.openBucket(bucketSettings.name(), bucketSettings.password()).subscribe({ bkt ->
        bkt.upsert(JsonDocument.create("helloworld", content))
          .subscribe({ result ->
            inserted.set(result)
            bkt.get("helloworld")
              .subscribe({ searchResult ->
                found.set(searchResult)
              })
          })
      })
    }

    // Create a JSON document and store it with the ID "helloworld"
    then:
    found.get() == inserted.get()
    found.get().content().getString("hello") == "world"

    assertTraces(1) {
      sortSpansByStart()
      trace(4) {
        basicSpan(it, "someTrace")

        assertCouchbaseCall(it, "Cluster.openBucket", null, span(0))
        assertCouchbaseCall(it, "Bucket.upsert", bucketSettings.name(), span(1))
        assertCouchbaseCall(it, "Bucket.get", bucketSettings.name(), span(2))
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)

    where:
    bucketSettings << [bucketCouchbase, bucketMemcache]

    environment = envBuilder(bucketSettings).build()
    cluster = CouchbaseAsyncCluster.create(environment, Arrays.asList("127.0.0.1"))
    type = bucketSettings.type().name()
  }

  def "test query"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    // Only couchbase buckets support queries.
    CouchbaseEnvironment environment = envBuilder(bucketCouchbase).build()
    AsyncCluster cluster = CouchbaseAsyncCluster.create(environment, Arrays.asList("127.0.0.1"))
    def queryResult = new BlockingVariable<JsonObject>(TIMEOUT)

    when:
    // Mock expects this specific query.
    // See com.couchbase.mock.http.query.QueryServer.handleString.
    runUnderTrace("someTrace") {
      cluster.openBucket(bucketCouchbase.name(), bucketCouchbase.password()).subscribe({ bkt ->
        bkt.query(N1qlQuery.simple("SELECT mockrow"))
          .flatMap({ query -> query.rows() })
          .single()
          .subscribe({ row -> queryResult.set(row.value()) })
      })
    }

    then:
    queryResult.get().get("row") == "value"

    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Cluster.openBucket", null, span(0))
        assertCouchbaseCall(it, "Bucket.query", bucketCouchbase.name(), span(1))
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)
  }
}
