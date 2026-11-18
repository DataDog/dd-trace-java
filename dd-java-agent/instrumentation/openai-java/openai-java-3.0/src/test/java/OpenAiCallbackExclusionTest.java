import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.openai_java.HttpResponseWrapper;
import datadog.trace.instrumentation.openai_java.HttpStreamResponseWrapper;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.tabletest.junit.TableTest;

@WithConfig(key = LLMOBS_ENABLED, value = "true")
class OpenAiCallbackExclusionTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario         | kind     | completed     | failure",
    "response success | response | {false, true} | false  ",
    "response failure | response | {false, true} | true   ",
    "stream success   | stream   | {false, true} | false  ",
    "stream failure   | stream   | {false, true} | true   "
  })
  @SuppressWarnings("unchecked")
  void wrapperKeepsHeaderCaptureButSkipsErrorCapture(
      String kind, boolean completed, boolean failure) throws Exception {
    CompletableFuture<HttpResponseFor<StreamResponse<String>>> source = new CompletableFuture<>();
    IllegalArgumentException cause = new IllegalArgumentException("request failed");
    Headers responseHeaders = Headers.builder().put("openai-organization", "test-org").build();
    AtomicReference<AgentSpan> headerParent = new AtomicReference<>();
    HttpResponseFor<StreamResponse<String>> response = mock(HttpResponseFor.class);
    when(response.headers())
        .thenAnswer(ignored -> {
          headerParent.set(activeSpan());
          return responseHeaders;
        });
    if (completed) {
      complete(source, response, failure ? cause : null);
    }
    AgentSpan span = startSpan("test", "openai.request");
    AgentSpan application = startSpan("test", "application");
    ApplicationCallback callback = new ApplicationCallback();
    ContextScope scope = activateSpan(span);
    int before = captures();
    try {
      CompletableFuture<HttpResponseFor<StreamResponse<String>>> dependent =
          kind.equals("response")
              ? HttpResponseWrapper.wrapFuture(source, span, new NoopDecorator<>())
              : HttpStreamResponseWrapper.wrapFuture(source, span, new NoopDecorator<>());
      int wrapperCaptures = captures() - before;
      scope.close();
      scope = activateSpan(application);
      before = captures();
      CompletableFuture<HttpResponseFor<StreamResponse<String>>> downstream =
          dependent.whenComplete(callback);
      int applicationCaptures = captures() - before;
      scope.close();
      scope = null;
      complete(source, response, failure ? cause : null);

      assertEquals(completed ? 0 : 1, wrapperCaptures);
      assertEquals(completed ? 0 : 1, applicationCaptures);
      assertEquals(1, callback.calls);
      assertSame(application, callback.parent);
      assertEquals(
          application.getSpanId(), assertInstanceOf(DDSpan.class, callback.child).getParentId());
      if (failure) {
        CompletionException expected = assertThrows(CompletionException.class, downstream::join);
        assertSame(cause, expected.getCause());
        assertNull(headerParent.get());
        verify(response, never()).close();
        assertTrue(span.isError());
        assertTrue(((String) span.getTag("error.stack")).contains("request failed"));
      } else {
        HttpResponseFor<StreamResponse<String>> wrapped = downstream.join();
        if (kind.equals("response")) {
          assertInstanceOf(HttpResponseWrapper.class, wrapped);
        } else {
          assertInstanceOf(HttpStreamResponseWrapper.class, wrapped);
        }
        assertSame(span, headerParent.get());
        assertEquals(0, writer.size());
        wrapped.close();
        verify(response).close();
        assertFalse(span.isError());
      }
      writer.waitForTraces(1);
      assertEquals(1, writer.get(0).size());
      assertNull(activeSpan());
    } finally {
      if (scope != null) {
        scope.close();
      }
      complete(source, response, failure ? cause : null);
      application.finish();
      span.finish();
      writer.waitForTraces(2);
      assertEquals(3, writer.stream().mapToInt(List::size).sum());
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static <T> void complete(CompletableFuture<T> source, T response, Throwable failure) {
    if (failure == null) {
      source.complete(response);
    } else {
      source.completeExceptionally(failure);
    }
  }

  private static class NoopDecorator<T> implements BiConsumer<AgentSpan, T> {
    @Override
    public void accept(AgentSpan span, T result) {}
  }

  private static class ApplicationCallback implements BiConsumer<Object, Throwable> {
    int calls;
    AgentSpan parent;
    AgentSpan child;

    @Override
    public void accept(Object value, Throwable failure) {
      calls++;
      parent = activeSpan();
      child = startSpan("test", "application.callback");
      child.finish();
    }
  }
}
