import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
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

class Lettuce5ReactiveClientTest extends AgentTestRunner {
  public static final String HOST = "127.0.0.1"
  public static final int DB_INDEX = 0
  // Disable autoreconnect so we do not get stray traces popping up on server shutdown
  public static final ClientOptions CLIENT_OPTIONS = ClientOptions.builder().autoReconnect(false).build()

  @Shared
  String embeddedDbUri

  @Shared
  RedisServer redisServer

  RedisClient redisClient
  StatefulConnection connection
  RedisReactiveCommands<String, ?> reactiveCommands
  RedisCommands<String, ?> syncCommands

  def setupSpec() {
    int port = PortUtils.randomOpenPort()
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
    setup:
    def conds = new AsyncConditions()
    Consumer<String> consumer = new Consumer<String>() {
      @Override
      void accept(String res) {
        conds.evaluate {
          assert res == "OK"
        }
      }
    }

    when:
    reactiveCommands.set("TESTSETKEY", "TESTSETVAL").subscribe(consumer)

    then:
    conds.await()
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }

  def "get command with lambda function"() {
    setup:
    def conds = new AsyncConditions()

    when:
    reactiveCommands.get("TESTKEY").subscribe { res -> conds.evaluate { assert res == "TESTVAL" } }

    then:
    conds.await()
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }

  // to make sure instrumentation's chained completion stages won't interfere with user's, while still
  // recording metrics
  def "get non existent key command"() {
    setup:
    def conds = new AsyncConditions()
    final defaultVal = "NOT THIS VALUE"

    when:
    reactiveCommands.get("NON_EXISTENT_KEY").defaultIfEmpty(defaultVal).subscribe {
      res ->
        conds.evaluate {
          assert res == defaultVal
        }
    }

    then:
    conds.await()
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }

  }

  def "command with no arguments"() {
    setup:
    def conds = new AsyncConditions()

    when:
    reactiveCommands.randomkey().subscribe {
      res ->
        conds.evaluate {
          assert res == "TESTKEY"
        }
    }

    then:
    conds.await()
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "RANDOMKEY"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }

  def "command flux publisher "() {
    setup:
    reactiveCommands.command().subscribe()

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName AGENT_CRASHING_COMMAND_PREFIX + "COMMAND"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            "db.command.results.count" 157
            defaultTags()
          }
        }
      }
    }
  }

  def "command cancel after 2 on flux publisher "() {
    setup:
    reactiveCommands.command().take(2).subscribe()

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName AGENT_CRASHING_COMMAND_PREFIX + "COMMAND"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            "db.command.cancelled" true
            "db.command.results.count" 2
            defaultTags()
          }
        }
      }
    }
  }

  def "non reactive command should not produce span"() {
    setup:
    String res = null

    when:
    res = reactiveCommands.digest()

    then:
    res != null
    TEST_WRITER.size() == 0
  }

  def "debug segfault command (returns mono void) with no argument should produce span"() {
    setup:
    reactiveCommands.debugSegfault().subscribe()

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName AGENT_CRASHING_COMMAND_PREFIX + "DEBUG"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }

  def "shutdown command (returns void) with argument should produce span"() {
    setup:
    reactiveCommands.shutdown(false).subscribe()

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "SHUTDOWN"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
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
    }

    then:
    sortAndAssertTraces(1) {
      trace(0, 3) {
        span(0) {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span(1) {
          childOf(span(0))
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
        span(2) {
          childOf(span(0))
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
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
        .then(reactiveCommands.get("a")) // The get here is ending up in another trace
        .subscribe()
    }

    then:
    sortAndAssertTraces(1) {
      trace(0, 3) {
        span(0) {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span(1) {
          childOf(span(0))
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
        span(2) {
          childOf(span(0))
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
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
        .then(reactiveCommands.get("a")) // The get here is ending up in another trace
        .subscribeOn(Schedulers.elastic())
        .subscribe()
    }

    then:
    sortAndAssertTraces(1) {
      trace(0, 3) {
        span(0) {
          operationName "test-parent"
          resourceName "test-parent"
          errored false

          tags {
            defaultTags()
          }
        }
        span(1) {
          childOf(span(0))
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "SET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
        span(2) {
          childOf(span(0))
          serviceName "redis"
          operationName "redis.query"
          spanType DDSpanTypes.REDIS
          resourceName "GET"
          errored false

          tags {
            "$Tags.COMPONENT" "redis-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "redis"
            defaultTags()
          }
        }
      }
    }
  }

  void sortAndAssertTraces(
    final int size,
    @ClosureParams(value = SimpleType, options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {

    TEST_WRITER.waitForTraces(size)

    TEST_WRITER.each {
      it.sort({
        a, b ->
          return a.startTime <=> b.startTime
      })
    }

    assertTraces(size, spec)
  }
}
