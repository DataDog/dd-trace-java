import com.netflix.hystrix.HystrixCommand
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.hystrix.HystrixDecorator
import spock.lang.Timeout

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

import static com.netflix.hystrix.HystrixCommandGroupKey.Factory.asKey
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Timeout(10)
class HystrixTest extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // Disable so failure testing below doesn't inadvertently change the behavior.
    System.setProperty("hystrix.command.default.circuitBreaker.enabled", "false")

    // hack to guarantee that that extra tags is enabled since it can't be guaranteed that
    // the Config singleton will not be initialised before this block runs.
    HystrixDecorator.DECORATE = new HystrixDecorator(true)

    // Uncomment for debugging:
    // System.setProperty("hystrix.command.default.execution.timeout.enabled", "false")
  }

  def "test command #action"() {
    setup:
    def command = new HystrixCommand<String>(asKey("ExampleGroup")) {
        @Override
        protected String run() throws Exception {
          return tracedMethod()
        }

        @Trace
        private String tracedMethod() {
          return "Hello!"
        }
      }
    def result = runUnderTrace("parent") {
      try {
        operation(command)
      } finally {
        blockUntilChildSpansFinished(2)
      }
    }
    expect:
    TRANSFORMED_CLASSES_NAMES.contains("com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler\$ThreadPoolWorker")
    TRANSFORMED_CLASSES_NAMES.contains("HystrixTest\$1")
    result == "Hello!"

    assertTraces(1) {
      trace(3) {
        span {
          operationName "parent"
          resourceName "parent"
          spanType null
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          operationName "hystrix.cmd"
          resourceName "ExampleGroup.HystrixTest\$1.execute"
          spanType null
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "hystrix"
            "hystrix.command" "HystrixTest\$1"
            "hystrix.group" "ExampleGroup"
            "hystrix.circuit-open" false
            defaultTags()
          }
        }
        span {
          operationName "trace.annotation"
          resourceName "HystrixTest\$1.tracedMethod"
          spanType null
          childOf span(1)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }

    where:
    action          | operation
    "execute"       | { HystrixCommand cmd -> cmd.execute() }
    "queue"         | { HystrixCommand cmd -> cmd.queue().get() }
    "toObservable"  | { HystrixCommand cmd -> cmd.toObservable().toBlocking().first() }
    "observe"       | { HystrixCommand cmd -> cmd.observe().toBlocking().first() }
    "observe block" | { HystrixCommand cmd ->
      BlockingQueue queue = new LinkedBlockingQueue()
      cmd.observe().subscribe { next ->
        queue.put(next)
      }
      queue.take()
    }
  }

  def "test command #action fallback"() {
    setup:
    def command = new HystrixCommand<String>(asKey("ExampleGroup")) {
        @Override
        protected String run() throws Exception {
          throw new IllegalArgumentException()
        }

        protected String getFallback() {
          return "Fallback!"
        }
      }
    def result = runUnderTrace("parent") {
      try {
        return operation(command)
      } finally {
        blockUntilChildSpansFinished(2)
      }
    }
    expect:
    TRANSFORMED_CLASSES_NAMES.contains("com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler\$ThreadPoolWorker")
    TRANSFORMED_CLASSES_NAMES.contains("HystrixTest\$2")
    result == "Fallback!"

    assertTraces(1) {
      trace(3) {
        span {
          operationName "parent"
          resourceName "parent"
          spanType null
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          operationName "hystrix.cmd"
          resourceName "ExampleGroup.HystrixTest\$2.execute"
          spanType null
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" "hystrix"
            "hystrix.command" "HystrixTest\$2"
            "hystrix.group" "ExampleGroup"
            "hystrix.circuit-open" false
            errorTags(IllegalArgumentException)
            defaultTags()
          }
        }
        span {
          operationName "hystrix.cmd"
          resourceName "ExampleGroup.HystrixTest\$2.fallback"
          spanType null
          childOf span(1)
          errored false
          tags {
            "$Tags.COMPONENT" "hystrix"
            "hystrix.command" "HystrixTest\$2"
            "hystrix.group" "ExampleGroup"
            "hystrix.circuit-open" false
            defaultTags()
          }
        }
      }
    }

    where:
    action          | operation
    "execute"       | { HystrixCommand cmd -> cmd.execute() }
    "queue"         | { HystrixCommand cmd -> cmd.queue().get() }
    "toObservable"  | { HystrixCommand cmd -> cmd.toObservable().toBlocking().first() }
    "observe"       | { HystrixCommand cmd -> cmd.observe().toBlocking().first() }
    "observe block" | { HystrixCommand cmd ->
      BlockingQueue queue = new LinkedBlockingQueue()
      cmd.observe().subscribe { next ->
        queue.put(next)
      }
      queue.take()
    }
  }
}
