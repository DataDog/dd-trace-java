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
    setup:
    System.out.println("test supplyAsync")
    System.err.println("test supplyAsync")


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
    setup:
    System.out.println("test thenApply $value")
    System.err.println("test thenApply $value")

    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def supply = CompletableFuture.supplyAsync {
        if ((value & 1) == 0) {
          Thread.sleep(10)
        }
        "done-$value"
      }
      if ((value & 2) == 0) {
        Thread.sleep(10)
      }
      supply.thenApply { result ->
        runUnderTrace("child") {
          result
        }
      }
    }

    then:
    completableFuture.get() == "done-$value"

    and:
    assertTraces(1) {
      // The parent and the child spans can finish out of order since they run
      // on different threads concurrently
      trace(0, 2) {
        def pIndex = span(0).isRootSpan() ? 0 : 1
        def cIndex = 1 - pIndex
        basicSpan(it, pIndex, "parent")
        basicSpan(it, cIndex, "child", span(pIndex))
      }
    }

    where:
    value << (0..((1 << 2) - 1))
  }

  def "test thenApplyAsync"() {
    setup:
    System.out.println("test thenApplyAsync $value")
    System.err.println("test thenApplyAsync $value")

    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      CompletableFuture<String> supply = CompletableFuture.supplyAsync {
        if ((value & 1) == 0) {
          Thread.sleep(10)
        }
        "done-$value"
      }
      if ((value & 2) == 0) {
        Thread.sleep(10)
      }
      def result = supply.thenApplyAsync { result ->
        if ((value & 4) == 0) {
          Thread.sleep(10)
        }
        runUnderTrace("child") {
          result
        }
      }
      blockUntilChildSpansFinished(1)
      result
    }

    then:
    completableFuture.get() == "done-$value"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }

    where:
    value << (0..((1 << 3) - 1))
  }

  def "test thenCompose"() {
    setup:
    System.out.println("test thenCompose $value")
    System.err.println("test thenCompose $value")

    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def supply = CompletableFuture.supplyAsync {
        if ((value & 1) == 0) {
          Thread.sleep(10)
        }
        "done-$value"
      }
      if ((value & 2) == 0) {
        Thread.sleep(10)
      }
      def result = supply.thenCompose { result ->
        CompletableFuture.supplyAsync {
          if ((value & 4) == 0) {
            Thread.sleep(10)
          }
          runUnderTrace("child") {
            result
          }
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done-$value"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }

    where:
    value << (0..((1 << 3) - 1))
  }

  def "test thenComposeAsync"() {
    setup:
    System.out.println("test thenComposeAsync $value")
    System.err.println("test thenComposeAsync $value")

    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def supply = CompletableFuture.supplyAsync {
        if ((value & 1) == 0) {
          Thread.sleep(10)
        }
        "done-$value"
      }
      if ((value & 2) == 0) {
        Thread.sleep(10)
      }
      def result = supply.thenComposeAsync { result ->
        def inner = CompletableFuture.supplyAsync {
          if ((value & 4) == 0) {
            Thread.sleep(10)
          }
          runUnderTrace("child") {
            result
          }
        }
        if ((value & 8) == 0) {
          Thread.sleep(10)
        }
        inner
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done-$value"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }

    where:
    value << (0..((1 << 4) - 1))
  }

  def "test compose and apply"() {
    setup:
    System.out.println("test compose and apply $value")
    System.err.println("test compose and apply $value")

    when:
    CompletableFuture<String> completableFuture = runUnderTrace("parent") {
      def supply = CompletableFuture.supplyAsync {
        if ((value & 1) == 0) {
          Thread.sleep(10)
        }
        "do"
      }
      if ((value & 2) == 0) {
        Thread.sleep(10)
      }
      def compose = supply.thenCompose { result ->
        CompletableFuture.supplyAsync {
          if ((value & 4) == 0) {
            Thread.sleep(10)
          }
          result + "ne-$value"
        }
      }
      def result = compose.thenApplyAsync { result ->
        if ((value & 8) == 0) {
          Thread.sleep(10)
        }
        runUnderTrace("child") {
          result
        }
      }
      blockUntilChildSpansFinished(1)
      return result
    }

    then:
    completableFuture.get() == "done-$value"

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
      }
    }

    where:
    value << (0..((1 << 4) - 1))
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
