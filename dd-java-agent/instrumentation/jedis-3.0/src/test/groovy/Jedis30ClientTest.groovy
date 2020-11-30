import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import redis.clients.jedis.Jedis
import redis.embedded.RedisServer
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class Jedis30ClientTest extends AgentTestRunner {

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
  Jedis jedis = new Jedis("localhost", port)

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    
    // This setting should have no effect since decorator returns null for the instance.
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
    println "Using redis: $redisServer.args"
    redisServer.start()
  }

  def cleanupSpec() {
    redisServer.stop()
    jedis.close()
  }

  def setup() {
    def cleanupSpan = runUnderTrace("cleanup") {
      jedis.flushAll()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan)
    TEST_WRITER.start()
  }

  def "set command"() {
    when:
    jedis.set("foo", "bar")

    then:
    assertTraces(1) {
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
    }
  }

  def "get command"() {
    when:
    jedis.set("foo", "bar")
    def value = jedis.get("foo")

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

  def "command with no arguments"() {
    when:
    jedis.set("foo", "bar")
    def value = jedis.randomKey()

    then:
    value == "foo"

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
          resourceName "RANDOMKEY"
          spanType DDSpanTypes.REDIS
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
