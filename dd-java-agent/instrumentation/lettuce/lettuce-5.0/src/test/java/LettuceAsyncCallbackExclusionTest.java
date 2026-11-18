import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.lettuce5.LettuceAsyncBiConsumer;
import datadog.trace.instrumentation.lettuce5.LettuceAsyncCommandsAdvice;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import org.tabletest.junit.TableTest;

class LettuceAsyncCallbackExclusionTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario               | completed | outcome",
    "pending success        | false     | success",
    "pending error          | false     | error  ",
    "pending cancellation   | false     | cancel ",
    "completed success      | true      | success",
    "completed error        | true      | error  ",
    "completed cancellation | true      | cancel "
  })
  void asyncCommandFinisherSkipsCapture(boolean completed, String outcome) throws Exception {
    Command<String, String, String> command =
        new Command<>(CommandType.GET, new StatusOutput<>(StringCodec.UTF8));
    // Construct outside a span: this test isolates callback capture from AsyncCommand's own
    // capture.
    AsyncCommand<String, String, String> source = new AsyncCommand<>(command);
    IOException error = new IOException("command failed");
    if (completed) {
      complete(source, outcome, error);
    }
    CompletableFuture<String> control = new CompletableFuture<>();
    ApplicationCallback application = new ApplicationCallback();
    ApplicationCallback ordinary = new ApplicationCallback();
    AgentSpan parent = startSpan("test", "parent");
    ContextScope parentScope = activateSpan(parent);
    AgentSpan clientSpan = startSpan("test", "redis");
    ContextScope clientScope = activateSpan(clientSpan);

    try {
      int before = captures();
      LettuceAsyncCommandsAdvice.stopSpan(command, clientScope, null, source);
      clientScope = null;
      int captures = captures() - before;
      CompletableFuture<String> downstream = source.whenComplete(application);
      before = captures();
      CompletableFuture<String> controlResult = control.whenComplete(ordinary);
      int controlCaptures = captures() - before;
      parentScope.close();
      parentScope = null;
      complete(source, outcome, error);
      control.complete("OK");

      assertEquals(0, captures);
      assertTrue(exclude(COMPLETABLE_FUTURE_CALLBACK, LettuceAsyncBiConsumer.class.getName()));
      assertEquals(1, controlCaptures);
      assertEquals(1, application.calls);
      assertSame(parent, application.span);
      assertSame(parent, ordinary.span);
      assertEquals("OK", controlResult.join());
      if ("success".equals(outcome)) {
        assertEquals("OK", downstream.join());
      } else {
        CompletionException expected = assertThrows(CompletionException.class, downstream::join);
        if ("cancel".equals(outcome)) {
          assertInstanceOf(CancellationException.class, expected.getCause());
        } else {
          assertSame(error, expected.getCause());
        }
      }
      assertEquals("error".equals(outcome), clientSpan.isError());
      assertEquals(
          "cancel".equals(outcome) ? Boolean.TRUE : null,
          clientSpan.getTag("db.command.cancelled"));
    } finally {
      if (clientScope != null) {
        clientScope.close();
      }
      if (parentScope != null) {
        parentScope.close();
      }
      complete(source, outcome, error);
      control.complete("OK");
      parent.finish();
      writer.waitForTraces(1);
      assertEquals(2, writer.get(0).size());
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static void complete(
      AsyncCommand<String, String, String> source, String outcome, Throwable error) {
    if (source.isDone()) {
      return;
    }
    if ("cancel".equals(outcome)) {
      source.cancel(false);
    } else if ("error".equals(outcome)) {
      source.completeExceptionally(error);
    } else {
      source.complete("OK");
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    AgentSpan span;
    int calls;

    @Override
    public void accept(String value, Throwable error) {
      span = activeSpan();
      calls++;
    }
  }
}
