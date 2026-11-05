import completablefuture.CallbackExclusionTestInstrumentation.ExcludedBinary
import completablefuture.CallbackExclusionTestInstrumentation.UnlistedBinarySubclass
import completablefuture.CallbackExclusionTestInstrumentation.UnlistedBinarySibling
import completablefuture.CallbackExclusionTestInstrumentation.Excluded
import completablefuture.CallbackExclusionTestInstrumentation.UnlistedSibling
import completablefuture.CallbackExclusionTestInstrumentation.UnlistedSubclass
import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.BiConsumer

import static completablefuture.CallbackExclusionTestInstrumentation.register
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude

class CompletableFutureCallbackExclusionTest extends InstrumentationSpecification {
  def setup() {
    assert exclude(COMPLETABLE_FUTURE_CALLBACK, Excluded.name): "Unary callback exclusion provider was not installed"
    assert exclude(COMPLETABLE_FUTURE_CALLBACK, ExcludedBinary.name): "Binary callback exclusion provider was not installed"
  }

  def "pending #operation captures exactly #expected for #kind"() {
    setup:
    def source = new CompletableFuture()
    def other = new CompletableFuture()
    def binaryKinds = [(Excluded): ExcludedBinary, (UnlistedSubclass): UnlistedBinarySubclass,
      (UnlistedSibling): UnlistedBinarySibling]
    def callback = (operation in ["handle", "combine"] ? binaryKinds[kind] : kind).newInstance()

    when:
    runUnderTrace("registration") {
      try {
        int before = ScopeDiagnostics.report().records().size()
        register(operation, source, other, callback)
        assert ScopeDiagnostics.report().records().size() - before == expected
      } finally {
        source.complete("value")
        other.complete("other")
      }
    }

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1

    where:
    [operation, kind] << [
      [
        "apply",
        "accept",
        "run",
        "complete",
        "handle",
        "exception",
        "compose",
        "combine",
        "bothAccept",
        "bothRun",
        "eitherApply",
        "eitherAccept",
        "eitherRun"
      ],
      [Excluded, UnlistedSubclass, UnlistedSibling]
    ].combinations()
    expected = kind == Excluded ? 0 : 1
  }

  def "callbackless #relay captures exactly once"() {
    setup:
    def source = new CompletableFuture()
    def other = new CompletableFuture()

    when:
    runUnderTrace("registration") {
      try {
        int before = ScopeDiagnostics.report().records().size()
        if (relay == "unary") {
          CompletableFuture.completedFuture("ready").thenCompose { source }
        } else if (relay == "either") {
          CompletableFuture.anyOf(source, other)
        } else {
          CompletableFuture.allOf(source, other)
        }
        assert ScopeDiagnostics.report().records().size() - before == expected
      } finally {
        source.complete("value")
        other.complete("other")
      }
    }

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1

    where:
    relay << ["unary", "binary", "either"]
    expected = relay == "either" && JavaVirtualMachine.isJavaVersionAtLeast(9) ? 0 : 1
  }

  def "#kind callback preserves downstream context on #outcome async=#async"() {
    setup:
    def source = new CompletableFuture()
    def callback = kind.newInstance()
    callback.fail = outcome == "callback failure"
    def queued = new ArrayDeque<Runnable>()
    Executor executor = { Runnable task -> queued.add(task) } as Executor
    def registrationSpan
    def applicationSpan
    def workerSpan
    def downstreamSpan
    CompletableFuture dependent
    CompletableFuture downstream
    if (outcome == "completed failure") {
      source.completeExceptionally(new IllegalArgumentException("source failure"))
    } else if (outcome == "completed") {
      source.complete("value")
    }

    when:
    runUnderTrace("registration") {
      registrationSpan = activeSpan()
      dependent = async ? source.whenCompleteAsync(callback, executor) : source.whenComplete(callback)
    }
    runUnderTrace("application") {
      applicationSpan = activeSpan()
      downstream = dependent.whenComplete({ value, error ->
        runUnderTrace("downstream") { downstreamSpan = activeSpan() }
      } as BiConsumer)
    }
    runUnderTrace("worker") {
      workerSpan = activeSpan()
      if (outcome == "failure") {
        source.completeExceptionally(new IllegalArgumentException("source failure"))
      } else if (outcome == "cancel") {
        source.cancel(false)
      } else {
        source.complete("value")
      }
      while (!queued.isEmpty()) {
        queued.remove().run()
      }
      assert activeSpan() == workerSpan
    }

    then:
    downstream.done
    dependent.completedExceptionally == (outcome in ["failure", "cancel", "callback failure", "completed failure"])
    callback.calls == 1
    callback.active == (outcome.startsWith("completed") && !async || kind != Excluded ? registrationSpan : workerSpan)
    downstreamSpan.parentId == applicationSpan.spanId
    activeSpan() == null
    TEST_WRITER.waitForTraces(3)
    TEST_WRITER.collectMany { it }.size() == 4

    cleanup:
    source.complete("cleanup")
    while (!queued.isEmpty()) {
      queued.remove().run()
    }

    where:
    [kind, outcome, async] << [
      [Excluded, UnlistedSibling],
      ["success", "failure", "cancel", "completed", "completed failure", "callback failure"],
      [false, true]
    ].combinations()
  }
}
