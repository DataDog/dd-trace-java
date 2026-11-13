package datadog.trace.instrumentation.aws.v2;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.context.ContextKey;
import datadog.context.ContextManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.http.ExecutionContext;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptorChain;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class AwsContextRestorationTest extends DDJavaSpecification {
  private AgentTracer.TracerAPI previousTracer;
  private ContextHandle previousContext;
  private AgentSpan span;
  private CoreTracer tracer;
  private TracingExecutionInterceptor interceptor;

  @BeforeEach
  void setUpContext() {
    ContextManager.allowTesting();
    previousContext = Context.current().checkpoint();
    previousTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    span = tracer.startSpan("test", "request");
    AgentTracer.forceRegister(tracer);
    interceptor = new TracingExecutionInterceptor(null);
  }

  @AfterEach
  void restoreContext() {
    previousContext.swap();
    AgentTracer.forceRegister(previousTracer);
    span.finish();
    tracer.close();
    AgentTracer.installLegacyContextManager();
  }

  @Test
  void synchronousRequestSwapsAndRestoresTheOriginalContext() {
    Context parent = Context.root().with(ContextKey.named("aws-parent"), "parent");
    parent.swap();
    Context.setCaptureEnabled(false);
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(TracingExecutionInterceptor.CONTEXT_ATTRIBUTE, span);
    interceptor.beforeTransmission(null, attributes);
    assertSame(parent, Context.current());

    ContextHandle previous =
        AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodEnter(requestContext(attributes));
    assertNotSame(parent, Context.current());
    AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodExit(previous);
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
  }

  @Test
  void nestedRequestsRestoreTheEnclosingTransmission() {
    Context parent = Context.root().with(ContextKey.named("aws-nested"), "parent");
    parent.swap();
    ExecutionAttributes outerAttributes = new ExecutionAttributes();
    outerAttributes.putAttribute(TracingExecutionInterceptor.CONTEXT_ATTRIBUTE, span);
    interceptor.beforeTransmission(null, outerAttributes);
    ContextHandle outer =
        AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodEnter(
            requestContext(outerAttributes));

    Context enclosingTransmission = Context.current();
    ExecutionAttributes innerAttributes = new ExecutionAttributes();
    innerAttributes.putAttribute(TracingExecutionInterceptor.CONTEXT_ATTRIBUTE, span);
    interceptor.beforeTransmission(null, innerAttributes);
    ContextHandle inner =
        AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodEnter(
            requestContext(innerAttributes));
    AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodExit(inner);
    assertSame(enclosingTransmission, Context.current());
    AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodExit(outer);
    assertSame(parent, Context.current());
  }

  @Test
  void asyncSubmissionRestoresContextEvenIfTheTransportLeavesAnotherSpanActive() {
    Context parent = Context.root().with(ContextKey.named("aws-async"), "parent");
    parent.swap();
    Context.setCaptureEnabled(false);
    ContextHandle previous = AwsHttpClientInstrumentation.AsyncHttpRequestAdvice.methodEnter();
    span.swap();

    AwsHttpClientInstrumentation.AsyncHttpRequestAdvice.methodExit(previous);

    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
  }

  static RequestExecutionContext requestContext(ExecutionAttributes attributes) {
    GetObjectRequest request = GetObjectRequest.builder().bucket("bucket").key("key").build();
    return RequestExecutionContext.builder()
        .originalRequest(request)
        .executionContext(
            ExecutionContext.builder()
                .signer((httpRequest, executionAttributes) -> httpRequest)
                .interceptorContext(InterceptorContext.builder().request(request).build())
                .interceptorChain(new ExecutionInterceptorChain(emptyList()))
                .executionAttributes(attributes)
                .build())
        .build();
  }
}
