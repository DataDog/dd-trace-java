import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import reactor.core.scheduler.Schedulers
import redis.embedded.RedisServer
import spock.lang.Shared
import spock.util.concurrent.AsyncConditions

import java.util.function.Consumer

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.AGENT_CRASHING_COMMAND_PREFIX

abstract class Lettuce5ReactiveClientTest extends VersionedNamingTestBase {
  public static final String HOST = "127.0.0.1"
  public static final int DB_INDEX = 0
  // Disable autoreconnect so we do not get stray traces popping up on server shutdown
  public static final ClientOptions CLIENT_OPTIONS = ClientOptions.builder().autoReconnect(false).build()

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  String embeddedDbUri

  @Shared
  int port

  @Shared
  RedisServer redisServer

  RedisClient redisClient
  StatefulConnection connection
  RedisReactiveCommands<String, ?> reactiveCommands
  RedisCommands<String, ?> syncCommands

  def setupSpec() {
    port = PortUtils.randomOpenPort()
    String dbAddr = HOST + ":" + port + "/" + DB_INDEX
    embeddedDbUri = "redis://" + dbAddr

    redisServer = RedisServer.builder()
      // bind to localhost to avoid firewall popup
      .setting("bind " + HOST)
      // set max memory to avoid problems in CI
      .setting("maxmemory 128M")
      .port(port).build()
  }

  def setup() {
    redisClient = RedisClient.create(embeddedDbUri)

    println "Using redis: $redisServer.args"
    redisServer.start()
    redisClient.setOptions(CLIENT_OPTIONS)

    connection = redisClient.connect()
    reactiveCommands = connection.reactive()
    syncCommands = connection.sync()

    syncCommands.set("TESTKEY", "TESTVAL")

    // 1 set + 1 connect trace
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.clear()
  }

  def cleanup() {
    connection.close()
    redisClient.shutdown()
    redisServer.stop()
  }

  def "set command with subscribe on a defined consumer"() {

    def conds = new AsyncConditions()
    Consumer<String> consumer = new Consumer<String>() {
        @Override
        void accept(String res) {
          System.err.println("start work")
          conds.evaluate {
            assert res == "OK"
          }
          System.err.println("end work")
        }
      }

    when:
    reactiveCommands.set("TESTSETKEY", "TESTSETVAL").subscribe(consumer)

    then:
    conds.await()
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "get command with lambda function"() {

    def conds = new AsyncConditions()

    when:
    reactiveCommands.get("TESTKEY").subscribe { res -> conds.evaluate { assert res == "TESTVAL" } }

    then:
    conds.await()
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  // to make sure instrumentation's chained completion stages won't interfere with user's, while still
  // recording metrics
  def "get non existent key command"() {

    def conds = new AsyncConditions()
    final defaultVal = "NOT THIS VALUE"

    when:
    reactiveCommands.get("NON_EXISTENT_KEY").defaultIfEmpty(defaultVal).subscribe { res ->
      conds.evaluate {
        assert res == defaultVal
      }
    }

    then:
    conds.await()
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }

  }

  def "command with no arguments"() {

    def conds = new AsyncConditions()

    when:
    reactiveCommands.randomkey().subscribe { res ->
      conds.evaluate {
        assert res == "TESTKEY"
      }
    }

    then:
    conds.await()
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "RANDOMKEY"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "command flux publisher "() {

    reactiveCommands.command().subscribe()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName AGENT_CRASHING_COMMAND_PREFIX + "COMMAND"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            "db.command.results.count" 157
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "command cancel after 2 on flux publisher "() {

    reactiveCommands.command().take(2).subscribe()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName AGENT_CRASHING_COMMAND_PREFIX + "COMMAND"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            "db.command.cancelled" true
            "db.command.results.count" 2
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "non reactive command should not produce span"() {

    String res = null

    when:
    res = reactiveCommands.digest()

    then:
    res != null
    TEST_WRITER.size() == 0
  }

  def "debug segfault command (returns mono void) with no argument should produce span"() {

    reactiveCommands.debugSegfault().subscribe()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName AGENT_CRASHING_COMMAND_PREFIX + "DEBUG"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "shutdown command (returns void) with argument should produce span"() {

    reactiveCommands.shutdown(false).subscribe()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "SHUTDOWN"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "blocking subscriber"() {

    when:
    runUnderTrace("test-parent") {
      reactiveCommands.set("a", "1")
        .then(reactiveCommands.get("a")) // The get here is ending up in another trace
        .block()

      reactiveCommands.command().then(reactiveCommands.get("a"))
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        span {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span {
          childOf(span(0))
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
        span {
          childOf(span(0))
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "blocking subscriber on flux"() {

    when:
    runUnderTrace("test-parent") {
      reactiveCommands.command().then(reactiveCommands.get("a")).block()
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        span {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span {
          childOf(span(0))
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "COMMAND-NAME:COMMAND"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            "db.command.results.count" 157
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
        span {
          childOf(span(0))
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "async subscriber"() {
    when:
    runUnderTrace("test-parent") {
      reactiveCommands.set("a", "1")
        .then(reactiveCommands.get("a")) // The get here is reported separately
        .subscribe()

      blockUntilChildSpansFinished(2)
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        span {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }

  def "async subscriber with specific thread pool"() {
    when:
    runUnderTrace("test-parent") {
      reactiveCommands.set("a", "1")
        .then(reactiveCommands.get("a"))
        .subscribeOn(Schedulers.newParallel("test"))
        .subscribe()

      blockUntilChildSpansFinished(2)
    }

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        span {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName service()
          operationName operation()
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" HOST
            "$Tags.PEER_PORT" port
            "$Tags.DB_TYPE" "redis"
            "db.redis.dbIndex" 0
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
  }
}

class Lettuce5ReactiveClientV0Test extends Lettuce5ReactiveClientTest {

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

class Lettuce5ReactiveClientV1ForkedTest extends Lettuce5ReactiveClientTest {

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

