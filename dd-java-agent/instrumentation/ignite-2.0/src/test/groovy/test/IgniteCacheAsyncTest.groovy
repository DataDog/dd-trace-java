package test

import datadog.trace.core.DDSpan
import org.apache.ignite.IgniteCache
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class IgniteCacheAsyncTest extends AbstractIgniteTest {

  @Shared
  IgniteCache cache

  def setup() {
    // Start with a fresh cache for each test
    cache = igniteClient.getOrCreateCache("testCache")
    def cleanupSpan = runUnderTrace("cleanup") {
      cache.clear()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan)
    TEST_WRITER.start()
  }

  def "put command"() {
    setup:

    when:

    runUnderTrace("test") {
      def future = cache.putAsync("abc", "123")

      return future.get(1, TimeUnit.SECONDS)
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "test")
        assertIgniteCall(it, "cache.putAsync", "testCache", false)
      }
    }
  }

  def "size command"() {
    setup:

    when:
    cache.put("foo", "bar")

    def result = runUnderTrace("test") {
      def future = cache.sizeAsync()

      return future.get(10, TimeUnit.SECONDS)
    }

    then:
    result == 1
    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        assertIgniteCall(it, "cache.put", "testCache")
      }
      trace(2) {
        basicSpan(it, "test")
        assertIgniteCall(it, "cache.sizeAsync", "testCache", false)
      }
    }
  }
}

class IgniteCacheAsyncV0ForkedTest extends IgniteCacheAsyncTest {

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

class IgniteCacheAsyncV1ForkedTest extends IgniteCacheAsyncTest {

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
