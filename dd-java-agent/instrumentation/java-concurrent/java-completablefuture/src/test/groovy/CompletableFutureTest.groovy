import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

/**
 * Note: ideally this should live with the rest of ExecutorInstrumentationTest,
 * but this code needs java8 so we put it here for now.
 */
class CompletableFutureTest extends AgentTestRunner {

  def "CompletableFuture test"() {
    setup:
    def pool = new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    def differentPool = new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    def supplier = new Supplier<String>() {
      @Override
      @Trace(operationName = "supplier")
      String get() {
        sleep(1000)
        return "a"
      }
    }

    def function = new Function<String, String>() {
      @Override
      @Trace(operationName = "function")
      String apply(String s) {
        return s + "c"
      }
    }

    def future = new Supplier<CompletableFuture<String>>() {
      @Override
      @Trace(operationName = "parent")
      CompletableFuture<String> get() {
        try {
          activeScope().setAsyncPropagation(true)
          return CompletableFuture.supplyAsync(supplier, pool)
            .thenCompose({ s -> CompletableFuture.supplyAsync(new AppendingSupplier(s), differentPool) })
            .thenApply(function)
        } finally {
          blockUntilChildSpansFinished(3)
        }
      }
    }.get()

    def result = future.get()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    result == "abc"

    TEST_WRITER.size() == 1
    trace.size() == 4
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "function"
    trace.get(1).parentId == trace.get(0).spanId
    trace.get(2).operationName == "appendingSupplier"
    trace.get(2).parentId == trace.get(0).spanId
    trace.get(3).operationName == "supplier"
    trace.get(3).parentId == trace.get(0).spanId

    cleanup:
    pool?.shutdown()
    differentPool?.shutdown()
  }

  def "test supplyAsync"() {
    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def result = CompletableFuture.supplyAsync {
        runUnderTrace("child") {
          "done"
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }
  }

  def "test thenApply"() {
    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      CompletableFuture.supplyAsync {
        "done"
      }.thenApply { result ->
        runUnderTrace("child") {
          result
        }
      }
    }

    then:
    completableFuture.get() == "done"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }
  }

  def "test thenApplyAsync"() {
    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def result = CompletableFuture.supplyAsync {
        "done"
      }.thenApplyAsync { result ->
        runUnderTrace("child") {
          result
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }
  }

  def "test thenCompose"() {
    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def result = CompletableFuture.supplyAsync {
        "done"
      }.thenCompose { result ->
        CompletableFuture.supplyAsync {
          runUnderTrace("child") {
            result
          }
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }
  }

  def "test thenComposeAsync"() {
    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def result = CompletableFuture.supplyAsync {
        "done"
      }.thenComposeAsync { result ->
        CompletableFuture.supplyAsync {
          runUnderTrace("child") {
            result
          }
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }
  }

  def "test compose and apply"() {
    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def result = CompletableFuture.supplyAsync {
        "do"
      }.thenCompose { result ->
        CompletableFuture.supplyAsync {
          result + "ne"
        }
      }.thenApplyAsync { result ->
        runUnderTrace("child") {
          result
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }
  }

  class AppendingSupplier implements Supplier<String> {
    String letter

    AppendingSupplier(String letter) {
      this.letter = letter
    }

    @Override
    @Trace(operationName = "appendingSupplier")
    String get() {
      return letter + "b"
    }
  }
}
