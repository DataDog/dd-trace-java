package datadog.trace.instrumentation.aws.v0;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.DefaultRequest;
import com.amazonaws.Request;
import com.amazonaws.services.s3.model.GetObjectRequest;
import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.context.ContextManager;
import datadog.trace.agent.tooling.WeakMaps;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.FieldBackedContextStores;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class AwsContextRestorationTest extends DDJavaSpecification {
  private CoreTracer tracer;
  private AgentTracer.TracerAPI previousTracer;
  private ContextHandle previousContext;
  private TracingRequestHandler handler;
  private AgentSpan parentSpan;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUpTracer() {
    WeakMaps.registerAsSupplier();
    previousTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    configureContextManager();
    previousContext = Context.current().checkpoint();
    parentSpan = tracer.startSpan("test", "parent");
    parentSpan.swap();
    handler =
        new TracingRequestHandler(
            null,
            (ContextStore)
                FieldBackedContextStores.getContextStore(
                    FieldBackedContextStores.getContextStoreId(
                        AmazonWebServiceRequest.class.getName(), Context.class.getName())));
  }

  void configureContextManager() {
    ContextManager.allowTesting();
  }

  @AfterEach
  void restoreTracer() {
    previousContext.swap();
    parentSpan.finish();
    tracer.close();
    AgentTracer.forceRegister(previousTracer);
    AgentTracer.installLegacyContextManager();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nestedRequestsRestoreTheirOwnContexts(boolean requestExecutor) {
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    Request<?> outer = request();
    Request<?> inner = request();
    ContextHandle outerContext = enter(requestExecutor);
    handler.beforeRequest(outer);
    Context outerRequestContext = Context.current();
    ContextHandle innerContext = enter(requestExecutor);
    handler.beforeRequest(inner);

    exit(requestExecutor, innerContext, inner, null);
    assertSame(outerRequestContext, Context.current());
    exit(requestExecutor, outerContext, outer, null);
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
    finish(outer);
    finish(inner);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void requestFailureRestoresTheParentAndFinishesTheRequestSpan(boolean requestExecutor) {
    Context parent = Context.current();
    Request<?> request = request();
    ContextHandle previous = enter(requestExecutor);
    handler.beforeRequest(request);
    DDSpan span =
        (DDSpan)
            AgentSpan.fromContext(
                request.getHandlerContext(TracingRequestHandler.CONTEXT_CONTEXT_KEY));
    exit(requestExecutor, previous, request, new IllegalStateException("request handler failed"));
    assertSame(parent, Context.current());
    assertNull(request.getHandlerContext(TracingRequestHandler.CONTEXT_CONTEXT_KEY));
    assertTrue(span.isFinished());
    assertTrue(span.isError());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failureBeforeTheHandlerLeavesTheCallerContextIntact(boolean requestExecutor) {
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    ContextHandle previous = enter(requestExecutor);
    exit(requestExecutor, previous, request(), new IllegalStateException("before handler"));
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restorationDoesNotRequireARequest(boolean requestExecutor) {
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    ContextHandle previous = enter(requestExecutor);
    AgentTracer.blackholeSpan().swap();
    exit(requestExecutor, previous, null, null);
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
  }

  private static ContextHandle enter(boolean requestExecutor) {
    return requestExecutor
        ? RequestExecutorInstrumentation.RequestExecutorAdvice.methodEnter()
        : AWSHttpClientInstrumentation.HttpClientAdvice.methodEnter();
  }

  private static void exit(
      boolean requestExecutor, ContextHandle previous, Request<?> request, Throwable error) {
    if (requestExecutor) {
      RequestExecutorInstrumentation.RequestExecutorAdvice.methodExit(previous, request, error);
    } else {
      AWSHttpClientInstrumentation.HttpClientAdvice.methodExit(previous, request, error);
    }
  }

  private static Request<?> request() {
    Request<?> request = new DefaultRequest<>(new GetObjectRequest("bucket", "key"), "AmazonS3");
    request.setEndpoint(URI.create("http://localhost"));
    return request;
  }

  private static void finish(Request<?> request) {
    AgentSpan.fromContext(request.getHandlerContext(TracingRequestHandler.CONTEXT_CONTEXT_KEY))
        .finish();
  }
}
