import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions.finishSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions.wrapAsyncResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class AsyncResultCallbackExclusionTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario         | factory | completed     | outcome                                                ",
    "direct finisher  | direct  | {false, true} | {success, plain, completion, execution, nested, cancel}",
    "pending future   | future  | false         | {success, plain, completion, execution, nested, cancel}",
    "completion stage | stage   | {false, true} | {success, plain, completion, execution, nested, cancel}"
  })
  void finisherSkipsCapture(String factory, boolean completed, String outcome) throws Exception {
    CompletableFuture<String> source = new CompletableFuture<>();
    IllegalArgumentException cause = new IllegalArgumentException("source failed");
    Throwable failure =
        "plain".equals(outcome)
            ? cause
            : "completion".equals(outcome)
                ? new CompletionException(cause)
                : "execution".equals(outcome)
                    ? new ExecutionException(cause)
                    : "nested".equals(outcome)
                        ? new CompletionException(new CompletionException(cause))
                        : null;
    if (completed) {
      complete(source, outcome, failure);
    }
    CompletionStage<String> stage = mock(CompletionStage.class);
    when(stage.whenComplete(any()))
        .thenAnswer(invocation -> source.whenComplete(invocation.getArgument(0)));
    AgentSpan parent = startSpan("test", "parent");
    ContextScope scope = activateSpan(parent);
    AgentSpan span = startSpan("test", "async");
    ApplicationCallback application = new ApplicationCallback();
    int before = captures();
    try {
      CompletableFuture<String> dependent =
          "direct".equals(factory)
              ? source.whenComplete(finishSpan(span))
              : (CompletableFuture<String>)
                  ("stage".equals(factory)
                      ? wrapAsyncResult(stage, CompletionStage.class, span)
                      : wrapAsyncResult(source, CompletableFuture.class, span));
      int finisherCaptures = captures() - before;
      before = captures();
      CompletableFuture<String> downstream = dependent.whenComplete(application);
      int applicationCaptures = captures() - before;
      scope.close();
      scope = null;
      complete(source, outcome, failure);

      assertEquals(0, finisherCaptures);
      assertEquals(completed ? 0 : 1, applicationCaptures);
      assertEquals(1, application.calls);
      assertSame(parent, application.parent);
      assertNotSame(source, dependent);
      if ("success".equals(outcome)) {
        assertEquals("value", downstream.join());
      } else {
        CompletionException expected = assertThrows(CompletionException.class, downstream::join);
        if ("cancel".equals(outcome)) {
          assertInstanceOf(CancellationException.class, expected.getCause());
        } else {
          assertTrue(expected == failure || expected.getCause() == failure);
        }
      }
      assertEquals(!"success".equals(outcome), span.isError());
      if (!"success".equals(outcome)) {
        assertEquals(
            "cancel".equals(outcome)
                ? CancellationException.class.getName()
                : "nested".equals(outcome)
                    ? CompletionException.class.getName()
                    : IllegalArgumentException.class.getName(),
            span.getTag("error.type"));
      }
      assertNull(activeSpan());
    } finally {
      if (scope != null) {
        scope.close();
      }
      complete(source, outcome, failure);
      parent.finish();
      writer.waitForTraces(1);
      assertEquals(2, writer.get(0).size());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"success", "plain", "cancel"})
  void alreadyCompletedFutureRemainsUnwrapped(String outcome) throws Exception {
    CompletableFuture<String> source = new CompletableFuture<>();
    complete(source, outcome, new IllegalArgumentException("source failed"));
    AgentSpan span = startSpan("test", "async");
    try (ContextScope scope = activateSpan(span)) {
      int before = captures();
      assertNull(wrapAsyncResult(source, CompletableFuture.class, span));
      assertEquals(before, captures());
      assertEquals(0, writer.size());
    } finally {
      span.finish();
      writer.waitForTraces(1);
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static void complete(
      CompletableFuture<String> source, String outcome, Throwable failure) {
    if ("cancel".equals(outcome)) {
      source.cancel(false);
    } else if (failure != null) {
      source.completeExceptionally(failure);
    } else {
      source.complete("value");
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    int calls;
    AgentSpan parent;

    @Override
    public void accept(String value, Throwable failure) {
      calls++;
      parent = activeSpan();
    }
  }
}
