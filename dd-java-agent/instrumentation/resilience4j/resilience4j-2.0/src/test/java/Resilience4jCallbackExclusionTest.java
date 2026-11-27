import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.resilience4j.Resilience4jSpan;
import datadog.trace.instrumentation.resilience4j.Resilience4jSpanDecorator;
import datadog.trace.instrumentation.resilience4j.WrapperWithContext.SupplierOfCompletionStageWithContext;
import datadog.trace.instrumentation.resilience4j.WrapperWithContext.SupplierOfFutureWithContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.tabletest.junit.TableTest;

class Resilience4jCallbackExclusionTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario        | kind   | completed     | failure       | nested       ",
    "stage finisher  | stage  | {false, true} | {false, true} | {false, true}",
    "future finisher | future | {false, true} | {false, true} | {false, true}"
  })
  void finisherSkipsCapture(String kind, boolean completed, boolean failure, boolean nested)
      throws Exception {
    CompletableFuture<String> source = new CompletableFuture<>();
    IllegalArgumentException cause = new IllegalArgumentException("delegate failed");
    if (completed) {
      complete(source, failure ? cause : null);
    }
    AgentSpan[] resilience = new AgentSpan[1];
    DDSpan[] delegateChild = new DDSpan[1];
    Supplier<CompletableFuture<String>> delegate =
        () -> {
          resilience[0] = activeSpan();
          delegateChild[0] = (DDSpan) startSpan("test", "delegate.child");
          delegateChild[0].finish();
          return source;
        };
    Supplier<?> wrapper =
        "stage".equals(kind)
            ? new SupplierOfCompletionStageWithContext<>(
                delegate::get, Resilience4jSpanDecorator.DECORATE, null)
            : new SupplierOfFutureWithContext<>(
                delegate::get, Resilience4jSpanDecorator.DECORATE, null);
    AgentSpan application = startSpan("test", "application");
    AgentSpan existing = nested ? Resilience4jSpan.start() : null;
    ContextScope scope = existing == null ? null : activateSpan(existing);
    ApplicationCallback callback = new ApplicationCallback();
    int before = captures();
    try {
      CompletableFuture<String> returned = (CompletableFuture<String>) wrapper.get();
      int finisherCaptures = captures() - before;
      assertSame(existing, activeSpan());
      if (scope != null) {
        scope.close();
      }
      scope = activateSpan(application);
      before = captures();
      CompletableFuture<String> downstream = returned.whenComplete(callback);
      int applicationCaptures = captures() - before;
      scope.close();
      scope = null;
      complete(source, failure ? cause : null);

      assertEquals(0, finisherCaptures);
      assertEquals(completed ? 0 : 1, applicationCaptures);
      assertEquals("future".equals(kind), returned == source);
      assertEquals("resilience4j", resilience[0].getOperationName().toString());
      assertEquals(resilience[0].getSpanId(), delegateChild[0].getParentId());
      assertEquals(1, callback.calls);
      assertSame(application, callback.parent);
      assertEquals(application.getSpanId(), callback.child.getParentId());
      if (failure) {
        CompletionException expected = assertThrows(CompletionException.class, downstream::join);
        assertSame(cause, expected.getCause());
      } else {
        assertEquals("value", downstream.join());
      }
      assertFalse(resilience[0].isError());
      if (nested) {
        assertSame(existing, resilience[0]);
        assertEquals(0, writer.size());
        existing.finish();
      }
      writer.waitForTraces(1);
      assertEquals(2, writer.get(0).size());
      assertNull(activeSpan());
    } finally {
      if (scope != null) {
        scope.close();
      }
      complete(source, failure ? cause : null);
      if (existing != null) {
        existing.finish();
      }
      application.finish();
      writer.waitForTraces(2);
      assertEquals(4, writer.stream().mapToInt(List::size).sum());
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static void complete(CompletableFuture<String> source, Throwable failure) {
    if (failure == null) {
      source.complete("value");
    } else {
      source.completeExceptionally(failure);
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    int calls;
    AgentSpan parent;
    DDSpan child;

    @Override
    public void accept(String value, Throwable failure) {
      calls++;
      parent = activeSpan();
      child = (DDSpan) startSpan("test", "application.callback");
      child.finish();
    }
  }
}
