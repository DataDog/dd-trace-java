package test


import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.ignite.Ignite
import org.apache.ignite.IgniteCache
import org.apache.ignite.Ignition
import org.apache.ignite.configuration.IgniteConfiguration
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder
import spock.lang.Shared

import javax.cache.CacheException
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class IgniteCacheFailureTest extends VersionedNamingTestBase {

  @Shared
  IgniteCache cache
  @Shared
  Ignite igniteServer, igniteClient

  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.ignite.enabled", "true")
  }

  def setup() {
    IgniteConfiguration serverConfig = new IgniteConfiguration()
    IgniteConfiguration clientConfig = new IgniteConfiguration()

    // Need temporary directories for the files that Ignite creates
    serverConfig.setWorkDirectory(
      File.createTempDir("igniteserver", UUID.randomUUID().toString()).toString())
    clientConfig.setWorkDirectory(
      File.createTempDir("igniteclient", UUID.randomUUID().toString()).toString())

    serverConfig.setIgniteInstanceName("igniteServer")
    clientConfig.setIgniteInstanceName("igniteClient")

    clientConfig.setClientMode(true)
    clientConfig.setNetworkTimeout(100)

    TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder()
    ipFinder.setAddresses(Collections.singletonList("127.0.0.1:47500..47509"))
    serverConfig.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(ipFinder))
    clientConfig.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(ipFinder))

    igniteServer = Ignition.start(serverConfig)
    igniteClient = Ignition.start(clientConfig)

    // Start with a fresh cache for each test
    cache = igniteClient.getOrCreateCache("testCache")
    def cleanupSpan = runUnderTrace("cleanup") {
      cache.clear()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan)
    TEST_WRITER.start()
  }

  def cleanup() {
    igniteClient?.close()
    igniteServer?.close()
  }

  def "server down"() {
    setup:
    igniteServer.close()

    when:
    cache.put("abc", "123")

    then:
    def ex = thrown(CacheException)

    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          resourceName "cache.put on testCache"
          operationName operation()
          spanType DDSpanTypes.CACHE
          errored true
          tags {
            "$Tags.COMPONENT" "ignite-cache"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "ignite"
            "ignite.operation" "cache.put"
            "ignite.cache.name" "testCache"
            if (igniteClient.name()) {
              "ignite.instance" igniteClient.name()
            }
            "ignite.version" igniteClient.version().toString()
            errorTags(ex.class, ex.message)
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  def "server down async"() {
    setup:

    igniteServer.close()

    when:
    runUnderTrace("test") {
      cache.putAsync("abc", "123")
        .get(10, TimeUnit.SECONDS)
    }

    then:
    def ex = thrown(CacheException)

    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "test", null, ex)
        span {
          serviceName service()
          resourceName "cache.putAsync on testCache"
          operationName operation()
          spanType DDSpanTypes.CACHE
          errored true
          tags {
            "$Tags.COMPONENT" "ignite-cache"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "ignite"
            "ignite.operation" "cache.putAsync"
            "ignite.cache.name" "testCache"
            if (igniteClient.name()) {
              "ignite.instance" igniteClient.name()
            }
            "ignite.version" igniteClient.version().toString()
            errorTags(ex.class, ex.message)
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }
}

class IgniteCacheFailureV0ForkedTest extends IgniteCacheFailureTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return AbstractIgniteTest.V0_SERVICE
  }

  @Override
  String operation() {
    return AbstractIgniteTest.V0_OPERATION
  }
}

class IgniteCacheFailureV1ForkedTest extends IgniteCacheFailureTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return AbstractIgniteTest.V1_SERVICE
  }

  @Override
  String operation() {
    return AbstractIgniteTest.V1_OPERATION
  }
}
