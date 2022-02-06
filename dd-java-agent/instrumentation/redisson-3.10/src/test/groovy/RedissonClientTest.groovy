import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.redisson.Redisson
import org.redisson.api.BatchOptions
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.redisson.config.SingleServerConfig
import redis.embedded.RedisServer
import spock.lang.Shared

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

  def cleanup() {
    redissonClient.getKeys().flushdb()
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

  def "bucket get and set command"() {
    when:
    redissonClient.getBucket("foo").set("bar")
    def value = redissonClient.getBucket("foo").getAndSet("barTwo")

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
          resourceName "GETSET"
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

  def "binary stream set command"() {
    when:
    redissonClient.getBinaryStream("foo").set("bar".getBytes())

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

  def "binary stream get command"() {
    when:
    redissonClient.getBinaryStream("foo").set("bar".getBytes())
    def value = redissonClient.getBinaryStream("foo").get()

    then:
    value == "bar".getBytes()

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

// Not supported by 'embedded-redis'
//  def "geospatial add command"() {
//    when:
//    redissonClient.getGeo("foo").add(
//      new GeoEntry(40.58921356383488, -105.06320045888452, "bar"))
//
//    then:
//    assertTraces(1) {
//      trace(1) {
//        span {
//          serviceName "redis"
//          operationName "redis.query"
//          resourceName "GEOADD"
//          spanType DDSpanTypes.REDIS
//          topLevel true
//          tags {
//            "$Tags.COMPONENT" "redis-command"
//            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
//            "$Tags.DB_TYPE" "redis"
//            defaultTags()
//          }
//        }
//      }
//    }
//  }

  def "bitset set command"() {
    when:
    redissonClient.getBitSet("foo").set(0, true)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SETBIT"
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

  def "bitset get command"() {
    when:
    redissonClient.getBitSet("foo").set(0, true)
    def value = redissonClient.getBitSet("foo").get(0)

    then:
    value

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SETBIT"
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
          resourceName "GETBIT"
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

  def "atomic long set command"() {
    when:
    redissonClient.getAtomicLong("foo").set(0)

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

  def "atomic long get command"() {
    when:
    redissonClient.getAtomicLong("foo").set(0)
    def value = redissonClient.getAtomicLong("foo").get()

    then:
    value == 0

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

  def "atomic long increment and get command"() {
    when:
    redissonClient.getAtomicLong("foo").set(0)
    def value = redissonClient.getAtomicLong("foo").incrementAndGet()

    then:
    value == 1

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
          resourceName "INCR"
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

  def "topic publish command"() {
    when:
    redissonClient.getTopic("foo").publish("bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "PUBLISH"
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

//  Issue with SUBSCRIBE resourceName not coming through
//  def "topic pub sub command"() {
//    when:
//    def value = new CompletableFuture<String>()
//    def topic = redissonClient.getTopic("foo")
//    topic.addListener(String.class, new MessageListener<String>() {
//      @Override
//      void onMessage(CharSequence channel, String msg) {
//        value.complete(msg)
//      }
//    })
//    topic.publish("bar")
//
//    then:
//    value.get() == "bar"
//
//    assertTraces(2) {
//      trace(1) {
//        span {
//          serviceName "redis"
//          operationName "redis.query"
//          resourceName "SUBSCRIBE"
//          spanType DDSpanTypes.REDIS
//          topLevel true
//          tags {
//            "$Tags.COMPONENT" "redis-command"
//            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
//            "$Tags.DB_TYPE" "redis"
//            defaultTags()
//          }
//        }
//      }
//      trace(1) {
//        span {
//          serviceName "redis"
//          operationName "redis.query"
//          resourceName "PUBLISH"
//          spanType DDSpanTypes.REDIS
//          tags {
//            "$Tags.COMPONENT" "redis-command"
//            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
//            "$Tags.DB_TYPE" "redis"
//            defaultTags()
//          }
//        }
//      }
//    }
//  }

  def "hyperloglog add command"() {
    when:
    redissonClient.getHyperLogLog("foo").add("bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "PFADD"
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

  def "hyperloglog get count command"() {
    when:
    redissonClient.getHyperLogLog("foo").add("bar")
    def count = redissonClient.getHyperLogLog("foo").count()

    then:
    count == 1

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "PFADD"
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
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "PFCOUNT"
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

  def "map put command"() {
    when:
    redissonClient.getMap("foo").put("fooMeOnce", "bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "EVAL"
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

  def "set add command"() {
    when:
    redissonClient.getSet("foo").add("bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SADD"
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

  def "set remove command"() {
    when:
    redissonClient.getSet("foo").add("bar")
    def removed = redissonClient.getSet("foo").remove("bar")

    then:
    removed

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SADD"
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
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SREM"
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

  def "set remove random command"() {
    when:
    redissonClient.getSet("foo").add("bar")
    def value = redissonClient.getSet("foo").removeRandom()

    then:
    value == "bar"

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SADD"
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
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SPOP"
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

  def "scored sorted add command"() {
    when:
    redissonClient.getScoredSortedSet("foo").add(0, "bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "ZADD"
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

  def "scored sorted get command"() {
    when:
    redissonClient.getScoredSortedSet("foo").add(0, "bar")
    def value = redissonClient.getScoredSortedSet("foo")[0]

    then:
    value == "bar"

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "ZADD"
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
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "ZSCAN"
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

  def "list add command"() {
    when:
    redissonClient.getList("foo").add("bar")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "RPUSH"
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

  def "list add and remove command"() {
    when:
    redissonClient.getList("foo").add("bar")
    def value = redissonClient.getList("foo").remove(0)

    then:
    value == "bar"

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "RPUSH"
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
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "LPOP"
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

// Not supported by 'embedded-redis'
//  def "stream add command"() {
//    when:
//    redissonClient.getStream("foo").add("fooMeOnce", "bar")
//
//    then:
//    assertTraces(1) {
//      trace(1) {
//        span {
//          serviceName "redis"
//          operationName "redis.query"
//          resourceName "XADD"
//          spanType DDSpanTypes.REDIS
//          topLevel true
//          tags {
//            "$Tags.COMPONENT" "redis-command"
//            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
//            "$Tags.DB_TYPE" "redis"
//            defaultTags()
//          }
//        }
//      }
//    }
//  }

  def "simple pipelining command"() {
    when:
    def batch = redissonClient.createBatch(BatchOptions.defaults())
    batch.getBucket("foo").setAsync("bar")
    batch.execute()

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

  def "bucket set and get pipelining commands"() {
    when:
    def batch = redissonClient.createBatch(BatchOptions.defaults())
    batch.getBucket("foo").setAsync("bar")
    def future = batch.getBucket("foo").getAsync()
    batch.execute()
    def result = future.get()

    then:
    result == "bar"

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SET;GET"
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

  def "complex pipelining commands"() {
    when:
    def batch = redissonClient.createBatch(BatchOptions.defaults())

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
        span {
          serviceName "redis"
          operationName "redis.query"
          resourceName "SET;SET;GET;RPUSH;RPUSH;LRANGE"
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
