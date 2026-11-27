package test

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.ignite.IgniteCache
import org.apache.ignite.configuration.IgniteConfiguration
import spock.lang.Shared

import javax.cache.CacheException
import java.util.concurrent.TimeUnit

abstract class IgniteCacheFailureTest extends IgniteBaseTest {
  @Shared
  IgniteCache cache

  def setup() {
    startIgnite { IgniteConfiguration clientConfig ->
      clientConfig.setNetworkTimeout(100)
    }

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
    stopIgnite()
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
    return V0_SERVICE
  }

  @Override
  String operation() {
    return V0_OPERATION
  }
}

class IgniteCacheFailureV1ForkedTest extends IgniteCacheFailureTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return V1_SERVICE
  }

  @Override
  String operation() {
    return V1_OPERATION
  }
}
