import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDSpanTypes.HTTP_SERVER;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.instrumentation.pekkohttp.DatadogAsyncHandlerWrapper;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpRequest$;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import org.apache.pekko.http.scaladsl.model.HttpResponse$;
import org.apache.pekko.http.scaladsl.model.Uri$;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scala.Function1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContext$;
import scala.concurrent.ExecutionContextExecutorService;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

/**
 * Verifies that completing the handler Future with the request context active does not propagate
 * that context to the Future exposed to Pekko. Blocking the simulated Pekko callback makes trace
 * retention deterministic.
 */
abstract class AbstractPekkoHttpAsyncHandlerWrapperTest extends AbstractInstrumentationTest {

  /**
   * The operation name is a {@code UTF8BytesString}; {@link SpanMatcher#operationName(String)}
   * compares object types, while the {@link Pattern} overload compares {@code CharSequence}
   * content.
   */
  private static final Pattern OPERATION_NAME = Pattern.compile("pekko-http\\.request");

  protected abstract boolean expectedCompletionPriority();

  @BeforeEach
  void verifyPropagationMode() throws Exception {
    // Load the injected helper only after the test agent is installed, then verify each concrete
    // variant is exercising the intended Scala Promise propagation mode.
    assertEquals(
        expectedCompletionPriority(),
        readStaticBoolean(
            Class.forName("datadog.trace.instrumentation.scala.PromiseHelper"),
            "completionPriority"),
        "Unexpected Scala Promise propagation mode");
  }

  private static boolean readStaticBoolean(final Class<?> type, final String name)
      throws Exception {
    final Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return (Boolean) field.get(null);
  }

  /**
   * Covers the failed-response path. On Scala 2.12 {@code Promise.resolveTry} allocates a fresh
   * {@code Failure}, which incidentally drops any context associated with the completing {@code
   * Try}, so this path exercises the root-context attachment only. Scala 2.13 passes the {@code
   * Try} through, so there it exercises both defenses.
   */
  @Test
  void doesNotPropagateRequestContextWhenHandlerFails() throws Exception {
    assertRequestTraceIsNotRetained(
        new Failure<>(new Exception("controller exception")),
        span().root().operationName(OPERATION_NAME).type(HTTP_SERVER).error());
  }

  /**
   * Covers the successful-response path. Both Scala generations pass a {@code Success} through
   * completion unchanged, so this is the case that pins the defensive {@code Try} copy in
   * completion-priority mode.
   */
  @Test
  void doesNotPropagateRequestContextWhenHandlerSucceeds() throws Exception {
    assertRequestTraceIsNotRetained(
        new Success<>(emptyResponse()),
        span().root().operationName(OPERATION_NAME).type(HTTP_SERVER).error(false));
  }

  private void assertRequestTraceIsNotRetained(
      final Try<HttpResponse> handlerResult, final SpanMatcher expectedSpan) throws Exception {
    try (AsyncHandlerWrapperReproducer reproducer =
        new AsyncHandlerWrapperReproducer(handlerResult)) {
      reproducer.start();

      assertTrue(reproducer.awaitFrameworkCallback(), "Framework callback did not start");
      assertTrue(
          writer.waitForTracesMax(1, 5),
          "Request trace was held by the contextless framework callback");
      assertTraces(trace(expectedSpan));
    }
  }

  @Test
  void propagatesFatalCallbackFailure() {
    assertCallbackFailure(new LinkageError("callback linkage failure"), true);
  }

  @Test
  void completesFutureWithNonFatalCallbackFailure() {
    assertCallbackFailure(new IllegalStateException("callback failure"), false);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void assertCallbackFailure(final Throwable failure, final boolean fatal) {
    Future<HttpResponse> handlerFuture = mock(Future.class);
    Context[] requestContext = new Context[1];
    DatadogAsyncHandlerWrapper wrapper =
        new DatadogAsyncHandlerWrapper(
            new AbstractFunction1<HttpRequest, Future<HttpResponse>>() {
              @Override
              public Future<HttpResponse> apply(HttpRequest request) {
                requestContext[0] = Context.current();
                return handlerFuture;
              }
            },
            mock(ExecutionContext.class));
    Future<HttpResponse> response = wrapper.apply(emptyRequest());
    try {
      ArgumentCaptor<Function1> callback = ArgumentCaptor.forClass(Function1.class);
      verify(handlerFuture).onComplete(callback.capture(), any());
      // Inject an error while reading the result to exercise the callback's exception boundary.
      Try<HttpResponse> result = mock(Try.class);
      when(result.isSuccess()).thenReturn(true);
      when(result.get()).thenThrow(failure);
      if (fatal) {
        assertSame(
            failure, assertThrows(LinkageError.class, () -> callback.getValue().apply(result)));
        assertFalse(response.isCompleted());
      } else {
        callback.getValue().apply(result);
        assertTrue(response.isCompleted());
        assertSame(failure, ((Failure<HttpResponse>) response.value().get()).exception());
      }
    } finally {
      // The injected failure happens before finishSpan can finish the request span.
      fromContext(requestContext[0]).finish();
    }
    assertTraces(trace(span().root().operationName(OPERATION_NAME).type(HTTP_SERVER).error(false)));
  }

  private static HttpRequest emptyRequest() {
    return HttpRequest$.MODULE$.apply(
        HttpRequest$.MODULE$.apply$default$1(),
        Uri$.MODULE$.apply("/exception"),
        HttpRequest$.MODULE$.apply$default$3(),
        HttpRequest$.MODULE$.apply$default$4(),
        HttpRequest$.MODULE$.apply$default$5());
  }

  private static HttpResponse emptyResponse() {
    return HttpResponse$.MODULE$.apply(
        HttpResponse$.MODULE$.apply$default$1(),
        HttpResponse$.MODULE$.apply$default$2(),
        HttpResponse$.MODULE$.apply$default$3(),
        HttpResponse$.MODULE$.apply$default$4());
  }

  private static final class AsyncHandlerWrapperReproducer implements AutoCloseable {
    private final Try<HttpResponse> handlerResult;
    private final Promise<HttpResponse> handlerPromise = Promise$.MODULE$.apply();
    private Context requestContext;
    private final CountDownLatch callbackStarted = new CountDownLatch(1);
    private final CountDownLatch releaseCallback = new CountDownLatch(1);

    private final ExecutionContextExecutorService handlerExecutor =
        ExecutionContext$.MODULE$.fromExecutorService(Executors.newSingleThreadExecutor());
    private final ExecutionContextExecutorService frameworkExecutor =
        ExecutionContext$.MODULE$.fromExecutorService(Executors.newSingleThreadExecutor());

    AsyncHandlerWrapperReproducer(final Try<HttpResponse> handlerResult) {
      this.handlerResult = handlerResult;
    }

    void start() {
      DatadogAsyncHandlerWrapper wrapper =
          new DatadogAsyncHandlerWrapper(
              new AbstractFunction1<HttpRequest, Future<HttpResponse>>() {
                @Override
                public Future<HttpResponse> apply(HttpRequest request) {
                  requestContext = Context.current();
                  return handlerPromise.future();
                }
              },
              handlerExecutor);

      Future<HttpResponse> response = wrapper.apply(emptyRequest());

      // Model a callback registered by Pekko after the wrapper has closed the request scope. It
      // should not inherit the request context when the handler Future completes.
      response.onComplete(
          new AbstractFunction1<Try<HttpResponse>, Void>() {
            @Override
            public Void apply(Try<HttpResponse> result) {
              callbackStarted.countDown();
              try {
                releaseCallback.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return null;
            }
          },
          frameworkExecutor);

      // Application futures normally complete from callbacks running with the propagated request
      // context. Model that completion path so the test also covers context captured at promise
      // dispatch time, not only at callback construction time.
      try (ContextScope ignored = requestContext.attach()) {
        handlerPromise.complete(handlerResult);
      }
    }

    boolean awaitFrameworkCallback() throws InterruptedException {
      return callbackStarted.await(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
      releaseCallback.countDown();
      handlerExecutor.shutdown();
      frameworkExecutor.shutdown();
      assertTrue(
          handlerExecutor.awaitTermination(5, TimeUnit.SECONDS),
          "Handler executor did not terminate");
      assertTrue(
          frameworkExecutor.awaitTermination(5, TimeUnit.SECONDS),
          "Framework executor did not terminate");
    }
  }
}

/** Runs the async-handler context-retention reproducer with default Scala Promise propagation. */
class PekkoHttpAsyncHandlerWrapperTest extends AbstractPekkoHttpAsyncHandlerWrapperTest {

  @Override
  protected boolean expectedCompletionPriority() {
    return false;
  }
}

/**
 * Runs the async-handler context-retention reproducer with completion-priority propagation, which
 * associates the completing context with the resolved {@code Try} instead of the thread.
 *
 * <p>{@link WithConfig} applies before the test agent is installed, so the Scala Promise
 * instrumentation registers the advice that creates that association.
 */
@WithConfig(key = "trace.integration.scala_promise_completion_priority.enabled", value = "true")
class PekkoHttpAsyncHandlerWrapperForkedTest extends AbstractPekkoHttpAsyncHandlerWrapperTest {

  @Override
  protected boolean expectedCompletionPriority() {
    return true;
  }
}
