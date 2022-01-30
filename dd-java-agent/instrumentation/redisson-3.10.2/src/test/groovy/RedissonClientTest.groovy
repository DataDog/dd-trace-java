import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.redisson.Redisson;
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.redisson.config.SingleServerConfig
import redis.embedded.RedisServer
import spock.lang.Shared

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class RedissonClientTest extends AgentTestRunner {

  @Shared
  int port = PortUtils.randomOpenPort()

  @Shared
  RedisServer redisServer = RedisServer.builder()
  // bind to localhost to avoid firewall popup
    .setting("bind 127.0.0.1")
  // set max memory to avoid problems in CI
    .setting("maxmemory 128M")
    .port(port).build()

  @Shared
  Config config = new Config()

  @Shared
  SingleServerConfig singleServerConfig = config.useSingleServer().setAddress("redis://127.0.0.1:${port}")

  @Shared
  RedissonClient redissonClient

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // This setting should have no effect since decorator returns null for the instance.
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
    println "Using redis: $redisServer.args"
    redisServer.start()
    redissonClient = Redisson.create(config)
  }

  def cleanupSpec() {
    redisServer.stop()
    redissonClient.shutdown()
  }

  def setup() {
    def cleanupSpan = runUnderTrace("cleanup") {
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan)
    TEST_WRITER.start()
  }

  def "bucket set command"() {
    when:
    redissonClient.getBucket("foo").set("bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SET"
          spanType DDSpanTypes.REDIS
          topLevel true
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }

  def "bucket get command"() {
    when:
    redissonClient.getBucket("foo").set("bar")
    def value = redissonClient.getBucket("foo").get()

    then:
    value == "bar"

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SET"
          spanType DDSpanTypes.REDIS
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "GET"
          spanType DDSpanTypes.REDIS
          topLevel true
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }
}
