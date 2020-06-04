import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CompletableFutureTest extends AgentTestRunner {

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
}
