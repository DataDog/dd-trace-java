import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.lettuce5.LettuceAsyncBiConsumer
import datadog.trace.instrumentation.lettuce5.LettuceAsyncCommandsAdvice
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.output.StatusOutput
import io.lettuce.core.protocol.AsyncCommand
import io.lettuce.core.protocol.Command
import io.lettuce.core.protocol.CommandType

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.BiConsumer

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK

class LettuceAsyncCallbackExclusionTest extends InstrumentationSpecification {
  def "async command finisher skips capture: completed=#completed outcome=#outcome"() {
    setup:
    def command = new Command(CommandType.GET, new StatusOutput(StringCodec.UTF8))
    // Construct outside a span: this test isolates callback capture from AsyncCommand's own capture.
    def source = new AsyncCommand(command)
    def error = new IOException("command failed")
    if (completed) {
      complete(source, outcome, error)
    }
    def control = new CompletableFuture<String>()
    def application = new ApplicationCallback()
    def ordinary = new ApplicationCallback()
    def parent = startSpan("test", "parent")
    def parentScope = activateSpan(parent)
    def clientSpan = startSpan("test", "redis")
    def clientScope = activateSpan(clientSpan)

    when:
    int before = ScopeDiagnostics.report().records().size()
    LettuceAsyncCommandsAdvice.stopSpan(command, clientScope, null, source)
    clientScope = null
    int captures = ScopeDiagnostics.report().records().size() - before
    def downstream = source.whenComplete(application)
    before = ScopeDiagnostics.report().records().size()
    def controlResult = control.whenComplete(ordinary)
    int controlCaptures = ScopeDiagnostics.report().records().size() - before
    parentScope.close()
    parentScope = null
    complete(source, outcome, error)
    control.complete("OK")

    then:
    captures == 0
    exclude(COMPLETABLE_FUTURE_CALLBACK, LettuceAsyncBiConsumer.name)
    controlCaptures == 1
    application.calls == 1
    application.span.is(parent)
    ordinary.span.is(parent)
    controlResult.join() == "OK"
    if (outcome == "success") {
      assert downstream.join() == "OK"
    } else {
      try {
        downstream.join()
        assert false: "expected exceptional completion"
      } catch (CompletionException expected) {
        assert outcome == "cancel" ? expected.cause instanceof java.util.concurrent.CancellationException : expected.cause.is(error)
      }
    }
    clientSpan.isError() == (outcome == "error")
    clientSpan.getTag("db.command.cancelled") == (outcome == "cancel" ? true : null)

    cleanup:
    clientScope?.close()
    parentScope?.close()
    complete(source, outcome, error)
    control.complete("OK")
    parent.finish()
    TEST_WRITER.waitForTraces(1)
    assert TEST_WRITER[0].size() == 2

    where:
    completed | outcome
    false     | "success"
    false     | "error"
    false     | "cancel"
    true      | "success"
    true      | "error"
    true      | "cancel"
  }

  private static void complete(AsyncCommand source, String outcome, Throwable error) {
    if (source.isDone()) {
      return
    }
    if (outcome == "cancel") {
      source.cancel(false)
    } else if (outcome == "error") {
      source.completeExceptionally(error)
    } else {
      source.complete("OK")
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    AgentSpan span
    int calls

    @Override
    void accept(String value, Throwable error) {
      span = activeSpan()
      calls++
    }
  }
}
