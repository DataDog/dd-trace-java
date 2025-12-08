import org.testcontainers.utility.DockerImageName

import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

import com.redis.testcontainers.RedisContainer
import com.redis.testcontainers.RedisServer
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.redisson.Config
import org.redisson.Redisson
import org.redisson.RedissonClient
import org.redisson.client.RedisClient
import org.redisson.client.RedisConnection
import org.redisson.client.protocol.RedisCommands
import org.testcontainers.containers.wait.strategy.Wait
import spock.lang.Shared

abstract class RedissonClientTest extends VersionedNamingTestBase {

  @Shared
  RedisServer redisServer = new RedisContainer(DockerImageName.parse("redis:6.2.6")).waitingFor(Wait.forListeningPort())

  @Shared
  Config config = new Config()

  @Shared
  RedissonClient redissonClient

  @Shared
  RedisClient lowLevelRedisClient

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // This setting should have no effect since decorator returns null for the instance.
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
    redisServer.start()
    println "Using redis: $redisServer.redisURI"
    config.useSingleServer().setAddress("$redisServer.host:$redisServer.firstMappedPort")
    redissonClient = Redisson.create(config)
    lowLevelRedisClient = new RedisClient(redisServer.host, redisServer.firstMappedPort)
  }

  def cleanupSpec() {
    redissonClient.shutdown()
    lowLevelRedisClient.shutdown()
    redisServer.stop()
  }

  def setup() {
    TEST_WRITER.start()
  }

  def cleanup() {
    try (def suppressScope = TEST_TRACER.muteTracing()) {
      RedisConnection conn = lowLevelRedisClient.connect()
      conn.sync(RedisCommands.FLUSHDB)
      conn.closeAsync().await()
    }
  }

  def "bucket set command"() {
    when:
    redissonClient.getBucket("foo").set("bar")

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "SET")
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
        redisSpan(it, "SET")
      }
      trace(1) {
        redisSpan(it, "GET")
      }
    }
  }

  def "atomic long set command"() {
    when:
    redissonClient.getAtomicLong("foo").set(0)

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "SET")
      }
    }
  }

  def "atomic long get command"() {
    when:
    redissonClient.getAtomicLong("foo").set(0)
    def value = redissonClient.getAtomicLong("foo").get()

    then:
    value == 0

    assertTraces(2) {
      trace(1) {
        redisSpan(it, "SET")
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "INCRBY"
          spanType DDSpanTypes.REDIS
          measured true
          tags {
            "$Tags.COMPONENT" "redis-command"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            "$Tags.PEER_HOSTNAME" redisServer.getHost()
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" redisServer.firstMappedPort
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "atomic long increment and get command"() {
    when:
    redissonClient.getAtomicLong("foo").set(0)
    def value = redissonClient.getAtomicLong("foo").incrementAndGet()

    then:
    value == 1

    assertTraces(2) {
      trace(1) {
        redisSpan(it, "SET")
      }
      trace(1) {
        redisSpan(it, "INCR")
      }
    }
  }

  def "topic publish command"() {
    when:
    redissonClient.getTopic("foo").publish("bar")

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "PUBLISH")
      }
    }
  }

  def "hyperloglog add command"() {
    when:
    redissonClient.getHyperLogLog("foo").add("bar")

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "PFADD")
      }
    }
  }

  def "hyperloglog get count command"() {
    when:
    redissonClient.getHyperLogLog("foo").add("bar")
    def count = redissonClient.getHyperLogLog("foo").count()

    then:
    count == 1

    assertTraces(2) {
      trace(1) {
        redisSpan(it, "PFADD")
      }
      trace(1) {
        redisSpan(it, "PFCOUNT")
      }
    }
  }

  def "map put command"() {
    when:
    redissonClient.getMap("foo").put("fooMeOnce", "bar")

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "EVAL")
      }
    }
  }

  def "set add command"() {
    when:
    redissonClient.getSet("foo").add("bar")

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "SADD")
      }
    }
  }

  def "set remove command"() {
    when:
    redissonClient.getSet("foo").add("bar")
    def removed = redissonClient.getSet("foo").remove("bar")

    then:
    removed

    assertTraces(2) {
      trace(1) {
        redisSpan(it, "SADD")
      }
      trace(1) {
        redisSpan(it, "SREM")
      }
    }
  }

  def "list add command"() {
    when:
    redissonClient.getList("foo").add("bar")

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "RPUSH")
      }
    }
  }

  def "simple pipelining command"() {
    when:
    def batch = redissonClient.createBatch()
    batch.getBucket("foo").setAsync("bar")
    batch.execute()

    then:
    assertTraces(1) {
      trace(1) {
        redisSpan(it, "SET")
      }
    }
  }

  def "bucket set and get pipelining commands"() {
    when:
    def batch = redissonClient.createBatch()
    batch.getBucket("foo").setAsync("bar")
    def future = batch.getBucket("foo").getAsync()
    batch.execute()
    def result = future.get()

    then:
    result == "bar"

    assertTraces(1) {
      trace(1) {
        redisSpan(it, "SET;GET")
      }
    }
  }

  def "complex pipelining commands"() {
    when:
    def batch = redissonClient.createBatch()

    batch.getBucket("bucket").setAsync("barOne")
    batch.getBucket("bucket").setAsync("barTwo")
    def futureBucketVal = batch.getBucket("bucket").getAsync()

    batch.getQueue("queue").offerAsync("qBarOne")
    batch.getQueue("queue").offerAsync("qBarTwo")
    def futureQueueValues = batch.getQueue("queue").readAllAsync()

    batch.execute()
    def bucketResult = futureBucketVal.get()
    def queueValues = futureQueueValues.get()

    then:
    bucketResult == "barTwo"
    queueValues.contains("qBarOne")
    queueValues.contains("qBarTwo")

    assertTraces(1) {
      trace(1) {
        redisSpan(it, "SET;SET;GET;RPUSH;RPUSH;LRANGE")
      }
    }
  }

  def redisSpan(TraceAssert traceAssert, String resource) {
    traceAssert.span {
      serviceName service()
      operationName operation()
      resourceName resource
      spanType DDSpanTypes.REDIS
      measured true
      tags {
        "$Tags.COMPONENT" "redis-command"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "redis"
        "$Tags.PEER_HOSTNAME" redisServer.getHost()
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" redisServer.firstMappedPort
        peerServiceFrom(Tags.PEER_HOSTNAME)
        defaultTags()
      }
    }
  }
}

class RedissonClientV0Test extends RedissonClientTest {

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

class RedissonClientV1ForkedTest extends RedissonClientTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return datadog.trace.api.Config.get().getServiceName()
  }

  @Override
  String operation() {
    return "redis.command"
  }
}

