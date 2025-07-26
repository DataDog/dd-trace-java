import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

import akka.actor.ActorSystem
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import redis.ByteStringDeserializerDefault
import redis.ByteStringSerializerLowPriority
import redis.RedisClient
import redis.RedisDispatcher
import redis.embedded.RedisServer
import scala.Option
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import spock.lang.Shared

abstract class RediscalaClientTest extends VersionedNamingTestBase {

  @Shared
  int port = PortUtils.randomOpenPort()

  @Shared
  RedisServer redisServer = RedisServer.newRedisServer()
  // bind to localhost to avoid firewall popup
  .setting("bind 127.0.0.1")
  // set max memory to avoid problems in CI
  .setting("maxmemory 128M")
  .port(port).build()

  @Shared
  ActorSystem system

  @Shared
  RedisClient redisClient

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // This setting should have no effect since decorator returns null for the instance.
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
    system = ActorSystem.create()
    redisClient = new RedisClient("localhost",
      port,
      Option.apply(null),
      Option.apply(null),
      "RedisClient",
      Option.apply(null),
      system,
      new RedisDispatcher("rediscala.rediscala-client-worker-dispatcher"))

    println "Using redis: $redisServer.@args"
    redisServer.start()
  }

  def cleanupSpec() {
    redisServer.stop()
    system?.terminate()
  }

  def setup() {
    TEST_WRITER.start()
  }

  def "set command"() {
    when:
    def value = redisClient.set("foo",
      "bar",
      Option.apply(null),
      Option.apply(null),
      false,
      false,
      new ByteStringSerializerLowPriority.String$())


    then:
    Await.result(value, Duration.apply("3 second")) == true
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "Set"
          spanType DDSpanTypes.REDIS
          measured true
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            "$Tags.PEER_HOSTNAME" redisClient.host()
            "$Tags.PEER_PORT" redisClient.port()
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "get command"() {
    when:
    def write = redisClient.set("bar",
      "baz",
      Option.apply(null),
      Option.apply(null),
      false,
      false,
      new ByteStringSerializerLowPriority.String$())
    def value = redisClient.get("bar", new ByteStringDeserializerDefault.String$())

    then:
    Await.result(write, Duration.apply("3 second")) == true
    Await.result(value, Duration.apply("3 second")) == Option.apply("baz")
    assertTraces(2) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "Set"
          spanType DDSpanTypes.REDIS
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            "$Tags.PEER_HOSTNAME" redisClient.host()
            "$Tags.PEER_PORT" redisClient.port()
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "Get"
          spanType DDSpanTypes.REDIS
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            "$Tags.PEER_HOSTNAME" redisClient.host()
            "$Tags.PEER_PORT" redisClient.port()
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }
}

class RediscalaClientV0Test extends RediscalaClientTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "redis"
  }

  @Override
  String operation() {
    return "redis.query"
  }
}

class RediscalaClientV1ForkedTest extends RediscalaClientTest {

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
    return "redis.command"
  }
}
