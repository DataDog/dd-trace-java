import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.valkey.Jedis
import redis.embedded.RedisServer

import spock.lang.Shared

abstract class ValkeyClientTest extends VersionedNamingTestBase {

  @Shared
  int port = PortUtils.randomOpenPort()

  @Shared
  RedisServer redisServer = RedisServer.newRedisServer()
  .port(port)
  .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
  .setting("maxmemory 128M")
  .build()

  @Shared
  Jedis jedis = new Jedis("localhost", port)

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
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
          serviceName service()
          operationName operation()
          resourceName "SET"
          spanType DDSpanTypes.VALKEY
          measured true
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
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
          serviceName service()
          operationName operation()
          resourceName "SET"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "GET"
          spanType DDSpanTypes.VALKEY
          measured true
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
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
          serviceName service()
          operationName operation()
          resourceName "SET"
          spanType DDSpanTypes.VALKEY
          measured true
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "RANDOMKEY"
          spanType DDSpanTypes.VALKEY
          measured true
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "hmset and hgetAll commands"() {
    when:

    Map<String, String> h = new HashMap<>()
    h.put("key1", "value1")
    h.put("key2", "value2")
    jedis.hmset("map", h)

    Map<String, String> result = jedis.hgetAll("map")

    then:
    result != null
    result == h

    assertTraces(2) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "HMSET"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "HGETALL"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "zadd and zrangeByScore commands"() {
    when:
    jedis.zadd("foo", 1d, "a")
    jedis.zadd("foo", 10d, "b")
    jedis.zadd("foo", 0.1d, "c")
    jedis.zadd("foo", 2d, "d")

    Set<String> verify = new HashSet<String>()
    verify.add("a")
    verify.add("c")
    verify.add("d")
    Set<String> val = jedis.zrangeByScore("foo", 0d, 2d)

    then:
    val != null
    val == verify

    assertTraces(5) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "ZADD"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "ZADD"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "ZADD"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "ZADD"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "ZRANGEBYSCORE"
          spanType DDSpanTypes.VALKEY
          tags {
            "$Tags.COMPONENT" "valkey-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "valkey"
            "$Tags.PEER_HOSTNAME" "localhost"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }
}

class ValkeyClientV0ForkedTest extends ValkeyClientTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "valkey"
  }

  @Override
  String operation() {
    return "valkey.query"
  }
}

class ValkeyClientV1ForkedTest extends ValkeyClientTest {

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
    return "valkey.command"
  }
}
