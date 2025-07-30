import com.couchbase.client.java.Bucket
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.CouchbaseCluster
import com.couchbase.client.java.document.JsonDocument
import com.couchbase.client.java.document.json.JsonObject
import com.couchbase.client.java.env.CouchbaseEnvironment
import com.couchbase.client.java.query.N1qlQuery
import datadog.trace.api.Config
import datadog.trace.test.util.Flaky
import util.AbstractCouchbaseTest

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Flaky
abstract class CouchbaseClientTest extends AbstractCouchbaseTest {
  def "test hasBucket #type"() {
    setup:

    when:
    def hasBucket = manager.hasBucket(bucketSettings.name())

    then:
    assert hasBucket
    assertTraces(1) {
      trace(1) {
        assertCouchbaseCall(it, "ClusterManager.hasBucket")
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)

    where:
    bucketSettings << [bucketCouchbase, bucketMemcache]

    environment = envBuilder(bucketSettings).build()
    cluster = CouchbaseCluster.create(environment, Arrays.asList("127.0.0.1"))
    manager = cluster.clusterManager(USERNAME, PASSWORD)
    type = bucketSettings.type().name()

    waitForTraces = {
      TEST_WRITER.waitForTraces(1) // from clusterManager above
      true
    }()
  }

  def "test upsert and get #type"() {
    setup:

    when:
    // Connect to the bucket and open it
    Bucket bkt = cluster.openBucket(bucketSettings.name(), bucketSettings.password())

    // Create a JSON document and store it with the ID "helloworld"
    JsonObject content = JsonObject.create().put("hello", "world")

    def inserted
    def found

    runUnderTrace("someTrace") {
      inserted = bkt.upsert(JsonDocument.create("helloworld", content))
      found = bkt.get("helloworld")
    }

    then:
    found == inserted
    found.content().getString("hello") == "world"

    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        assertCouchbaseCall(it, "Cluster.openBucket")
      }
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Bucket.upsert", bucketSettings.name(), span(0))
        assertCouchbaseCall(it, "Bucket.get", bucketSettings.name(), span(0))
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)

    where:
    bucketSettings << [bucketCouchbase, bucketMemcache]

    environment = envBuilder(bucketSettings).build()
    cluster = CouchbaseCluster.create(environment, Arrays.asList("127.0.0.1"))
    type = bucketSettings.type().name()
  }

  def "test query"() {
    setup:

    // Only couchbase buckets support queries.
    CouchbaseEnvironment environment = envBuilder(bucketCouchbase).build()
    Cluster cluster = CouchbaseCluster.create(environment, Arrays.asList("127.0.0.1"))
    Bucket bkt = cluster.openBucket(bucketCouchbase.name(), bucketCouchbase.password())

    when:
    // Mock expects this specific query.
    // See com.couchbase.mock.http.query.QueryServer.handleString.
    def result = bkt.query(N1qlQuery.simple("SELECT mockrow"))

    then:
    result.parseSuccess()
    result.finalSuccess()
    result.first().value().get("row") == "value"

    and:
    assertTraces(2) {
      trace(1) {
        assertCouchbaseCall(it, "Cluster.openBucket")
      }
      trace(1) {
        assertCouchbaseCall(it, "Bucket.query", bucketCouchbase.name())
      }
    }

    cleanup:
    cleanupCluster(cluster, environment)
  }
}

class CouchbaseClientV0ForkedTest extends CouchbaseClientTest {
}

class CouchbaseClientV1ForkedTest extends CouchbaseClientTest {
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
