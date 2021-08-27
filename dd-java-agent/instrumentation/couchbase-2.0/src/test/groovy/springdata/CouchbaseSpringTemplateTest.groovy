package springdata

import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import com.couchbase.client.java.Bucket
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.CouchbaseCluster
import com.couchbase.client.java.cluster.ClusterManager
import com.couchbase.client.java.env.CouchbaseEnvironment
import org.springframework.data.couchbase.core.CouchbaseTemplate
import spock.lang.Retry
import spock.lang.Shared
import spock.lang.Unroll
import util.AbstractCouchbaseTest

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@Retry(count = 10, delay = 500)
@Unroll
class CouchbaseSpringTemplateTest extends AbstractCouchbaseTest {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  List<CouchbaseTemplate> templates

  @Shared
  Cluster couchbaseCluster

  @Shared
  Cluster memcacheCluster

  @Shared
  protected CouchbaseEnvironment couchbaseEnvironment
  @Shared
  protected CouchbaseEnvironment memcacheEnvironment

  def setupSpec() {
    def setupSpan = runUnderTrace("setup") {
      couchbaseEnvironment = envBuilder(bucketCouchbase).build()
      memcacheEnvironment = envBuilder(bucketMemcache).build()

      couchbaseCluster = CouchbaseCluster.create(couchbaseEnvironment, Arrays.asList("127.0.0.1"))
      memcacheCluster = CouchbaseCluster.create(memcacheEnvironment, Arrays.asList("127.0.0.1"))
      ClusterManager couchbaseManager = couchbaseCluster.clusterManager(USERNAME, PASSWORD)
      ClusterManager memcacheManager = memcacheCluster.clusterManager(USERNAME, PASSWORD)

      Bucket bucketCouchbase = couchbaseCluster.openBucket(bucketCouchbase.name(), bucketCouchbase.password())
      Bucket bucketMemcache = memcacheCluster.openBucket(bucketMemcache.name(), bucketMemcache.password())

      templates = [
        new CouchbaseTemplate(couchbaseManager.info(), bucketCouchbase),
        new CouchbaseTemplate(memcacheManager.info(), bucketMemcache)
      ]
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(setupSpan)
  }

  def cleanupSpec() {
    couchbaseCluster?.disconnect()
    memcacheCluster?.disconnect()
    couchbaseEnvironment.shutdown()
    memcacheEnvironment.shutdown()
  }

  def "test write #name"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
    def doc = new Doc()
    def result

    when:
    runUnderTrace("someTrace") {
      template.save(doc)
      result = template.findById("1", Doc)
    }


    then:
    result != null

    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Bucket.upsert", name, span(0))
        assertCouchbaseCall(it, "Bucket.get", name, span(0))
      }
    }

    where:
    template << templates
    name = template.couchbaseBucket.name()
  }

  def "test remove #name"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
    def doc = new Doc()

    when:
    runUnderTrace("someTrace") {
      template.save(doc)
      template.remove(doc)
    }


    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Bucket.upsert", name, span(0))
        assertCouchbaseCall(it, "Bucket.remove", name, span(0))
      }
    }

    when:
    TEST_WRITER.clear()
    def result = template.findById("1", Doc)

    then:
    result == null
    assertTraces(1) {
      trace(1) {
        assertCouchbaseCall(it, "Bucket.get", name)
      }
    }

    where:
    template << templates
    name = template.couchbaseBucket.name()
  }
}
