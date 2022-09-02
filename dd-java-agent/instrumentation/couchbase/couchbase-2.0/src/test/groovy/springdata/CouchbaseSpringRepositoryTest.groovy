package springdata

import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.CouchbaseCluster
import com.couchbase.client.java.env.CouchbaseEnvironment
import com.couchbase.client.java.view.DefaultView
import com.couchbase.client.java.view.DesignDocument
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.repository.CrudRepository
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Unroll
import util.AbstractCouchbaseTest

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@IgnoreIf({
  // TODO Java 17: This version of spring-data doesn't support Java 17
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)
})
@Unroll
class CouchbaseSpringRepositoryTest extends AbstractCouchbaseTest {
  static final Closure<Doc> FIND
  static {
    // This method is different in Spring Data 2+
    try {
      CrudRepository.getMethod("findOne", Serializable)
      FIND = { DocRepository repo, String id ->
        repo.findOne(id)
      }
    } catch (NoSuchMethodException e) {
      FIND = { DocRepository repo, String id ->
        repo.findById(id).get()
      }
    }
  }

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  ConfigurableApplicationContext applicationContext
  @Shared
  DocRepository repo

  def setupSpec() {
    CouchbaseEnvironment environment = envBuilder(bucketCouchbase).build()
    Cluster couchbaseCluster = CouchbaseCluster.create(environment, Arrays.asList("127.0.0.1"))

    def setupSpan = runUnderTrace("setup") {

      // Create view for SpringRepository's findAll()
      couchbaseCluster.openBucket(bucketCouchbase.name(), bucketCouchbase.password()).bucketManager()
        .insertDesignDocument(
        DesignDocument.create("doc", Collections.singletonList(DefaultView.create("all",
        '''
          function (doc, meta) {
             if (doc._class == "springdata.Doc") {
               emit(meta.id, null);
             }
          }
        '''.stripIndent()
        )))
        )
      CouchbaseConfig.setEnvironment(environment)
      CouchbaseConfig.setBucketSettings(bucketCouchbase)

      // Close all buckets and disconnect
      couchbaseCluster.disconnect()

      activeSpan()
    }
    TEST_WRITER.waitUntilReported(setupSpan)

    applicationContext = new AnnotationConfigApplicationContext(CouchbaseConfig)
    repo = applicationContext.getBean(DocRepository)
  }

  def cleanupSpec() {
    applicationContext.close()
  }

  def cleanup() {
    def cleanupSpan = runUnderTrace("cleanup") {
      repo.deleteAll()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan)
  }

  def "test empty repo"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)


    when:
    def result = repo.findAll()

    then:
    !result.iterator().hasNext()

    and:
    assertTraces(1) {
      trace(1) {
        assertCouchbaseCall(it, "Bucket.query", bucketCouchbase.name())
      }
    }
  }

  def "test save"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def doc = new Doc()

    when:
    def result = repo.save(doc)

    then:
    result == doc
    assertTraces(1) {
      trace(1) {
        assertCouchbaseCall(it, "Bucket.upsert", bucketCouchbase.name())
      }
    }
  }

  def "test save and retrieve"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def doc = new Doc()
    def result

    when:
    runUnderTrace("someTrace") {
      repo.save(doc)
      result = FIND(repo, "1")
    }

    then: // RETRIEVE
    result == doc
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Bucket.upsert", bucketCouchbase.name(), span(0))
        assertCouchbaseCall(it, "Bucket.get", bucketCouchbase.name(), span(0))
      }
    }
  }

  def "test save and update"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def doc = new Doc()

    when:
    runUnderTrace("someTrace") {
      repo.save(doc)
      doc.data = "other data"
      repo.save(doc)
    }


    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Bucket.upsert", bucketCouchbase.name(), span(0))
        assertCouchbaseCall(it, "Bucket.upsert", bucketCouchbase.name(), span(0))
      }
    }
  }

  def "save and delete"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def doc = new Doc()
    def result

    when: // DELETE
    runUnderTrace("someTrace") {
      repo.save(doc)
      repo.delete("1")
      result = repo.findAll().iterator().hasNext()
    }

    then:
    assert !result
    assertTraces(1) {
      sortSpansByStart()
      trace(4) {
        basicSpan(it, "someTrace")
        assertCouchbaseCall(it, "Bucket.upsert", bucketCouchbase.name(), span(0))
        assertCouchbaseCall(it, "Bucket.remove", bucketCouchbase.name(), span(0))
        assertCouchbaseCall(it, "Bucket.query", bucketCouchbase.name(), span(0))
      }
    }
  }
}
