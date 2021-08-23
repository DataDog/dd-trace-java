package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.AWS_HTTP;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS request execution interceptor */
public class TracingExecutionInterceptor implements ExecutionInterceptor {

  private static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      new ExecutionAttribute<>("DatadogSpan");

  @Override
  public void beforeExecution(
      final Context.BeforeExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = startSpan(AWS_HTTP);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, span);
    }
  }

  @Override
  public void afterMarshalling(
      final Context.AfterMarshalling context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);

    DECORATE.onRequest(span, context.httpRequest());
    DECORATE.onSdkRequest(span, context.request());
    DECORATE.onAttributes(span, executionAttributes);
  }

  @Override
  public void beforeTransmission(
      final Context.BeforeTransmission context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);

    // This scope will be closed by AwsHttpClientInstrumentation since ExecutionInterceptor API
    // doesn't provide a way to run code in the same thread after transmission has been scheduled.
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    scope.span().startThreadMigration();
  }

  @Override
  public void afterExecution(
      final Context.AfterExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      span.finishThreadMigration();
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, null);
      // Call onResponse on both types of responses:
      DECORATE.onResponse(span, context.response());
      DECORATE.onResponse(span, context.httpResponse());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onExecutionFailure(
      final Context.FailedExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, null);
      DECORATE.onError(span, context.exception());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public static void muzzleCheck() {
    // Noop
  }
}
