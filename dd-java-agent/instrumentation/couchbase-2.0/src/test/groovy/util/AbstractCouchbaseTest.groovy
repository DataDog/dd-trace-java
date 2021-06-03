package util

import com.couchbase.client.core.metrics.DefaultLatencyMetricsCollectorConfig
import com.couchbase.client.core.metrics.DefaultMetricsCollectorConfig
import com.couchbase.client.java.CouchbaseAsyncCluster
import com.couchbase.client.java.CouchbaseCluster
import com.couchbase.client.java.bucket.BucketType
import com.couchbase.client.java.cluster.BucketSettings
import com.couchbase.client.java.cluster.DefaultBucketSettings
import com.couchbase.client.java.env.CouchbaseEnvironment
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment
import com.couchbase.mock.Bucket
import com.couchbase.mock.BucketConfiguration
import com.couchbase.mock.CouchbaseMock
import com.couchbase.mock.http.query.QueryServer
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class AbstractCouchbaseTest extends AgentTestRunner {

  static final USERNAME = "Administrator"
  static final PASSWORD = "password"

  @Shared
  private int port = PortUtils.randomOpenPort()

  @Shared
  private String testBucketName = this.getClass().simpleName

  @Shared
  protected bucketCouchbase = DefaultBucketSettings.builder()
  .enableFlush(true)
  .name("$testBucketName-cb")
  .password("test-pass")
  .type(BucketType.COUCHBASE)
  .quota(100)
  .build()

  @Shared
  protected bucketMemcache = DefaultBucketSettings.builder()
  .enableFlush(true)
  .name("$testBucketName-mem")
  .password("test-pass")
  .type(BucketType.MEMCACHED)
  .quota(100)
  .build()

  @Shared
  CouchbaseMock mock

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // This setting should have no effect since decorator returns null for the instance.
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
    mock = new CouchbaseMock("127.0.0.1", port, 1, 1)
    mock.httpServer.register("/query", new QueryServer())
    mock.start()
    println "CouchbaseMock listening on localhost:$port"

    mock.createBucket(convert(bucketCouchbase))
    mock.createBucket(convert(bucketMemcache))
  }

  private static BucketConfiguration convert(BucketSettings bucketSettings) {
    def configuration = new BucketConfiguration()
    configuration.name = bucketSettings.name()
    configuration.password = bucketSettings.password()
    configuration.type = Bucket.BucketType.valueOf(bucketSettings.type().name())
    configuration.numNodes = 1
    configuration.numReplicas = 0
    return configuration
  }

  def cleanupSpec() {
    mock?.stop()
  }

  protected DefaultCouchbaseEnvironment.Builder envBuilder(BucketSettings bucketSettings) {
    // Couchbase seems to be really slow to start sometimes
    def timeout = TimeUnit.SECONDS.toMillis(20)
    return DefaultCouchbaseEnvironment.builder()
      .bootstrapCarrierDirectPort(mock.getCarrierPort(bucketSettings.name()))
      .bootstrapHttpDirectPort(port)
      // settings to try to reduce variability in the tests:
      .runtimeMetricsCollectorConfig(DefaultMetricsCollectorConfig.create(0, TimeUnit.DAYS))
      .networkLatencyMetricsCollectorConfig(DefaultLatencyMetricsCollectorConfig.create(0, TimeUnit.DAYS))
      .computationPoolSize(1)
      .connectTimeout(timeout)
      .disconnectTimeout(timeout)
      .kvTimeout(timeout)
      .managementTimeout(timeout)
      .queryTimeout(timeout)
      .viewTimeout(timeout)
      .keepAliveTimeout(timeout)
      .searchTimeout(timeout)
      .analyticsTimeout(timeout)
      .socketConnectTimeout(timeout.intValue())
  }

  protected void cleanupCluster(CouchbaseAsyncCluster cluster, CouchbaseEnvironment environment) {
    def cleanupSpan = runUnderTrace("cleanup") {
      try {
        cluster?.disconnect()?.timeout(10, TimeUnit.SECONDS)?.toBlocking()?.single()
        environment.shutdown()
      } catch (Throwable ex) {
        // ignore
      }
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan, 60, TimeUnit.SECONDS)
  }

  protected void cleanupCluster(CouchbaseCluster cluster, CouchbaseEnvironment environment) {
    def cleanupSpan = runUnderTrace("cleanup") {
      cluster?.disconnect()
      environment.shutdown()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan)
  }

  void assertCouchbaseCall(TraceAssert trace, String name, String bucketName = null, Object parentSpan = null) {
    trace.span {
      serviceName "couchbase"
      resourceName name
      operationName "couchbase.call"
      spanType DDSpanTypes.COUCHBASE
      errored false
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      tags {
        "$Tags.COMPONENT" "couchbase-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "couchbase"
        if (bucketName != null) {
          "bucket" bucketName
        }
        defaultTags()
      }
    }
  }
}
