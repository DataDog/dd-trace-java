package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpanWithoutScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.AWS_LEGACY_TRACING;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context.AfterExecution;
import software.amazon.awssdk.core.interceptor.Context.AfterMarshalling;
import software.amazon.awssdk.core.interceptor.Context.BeforeExecution;
import software.amazon.awssdk.core.interceptor.Context.BeforeTransmission;
import software.amazon.awssdk.core.interceptor.Context.FailedExecution;
import software.amazon.awssdk.core.interceptor.Context.ModifyHttpRequest;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;

/** AWS request execution interceptor */
public class TracingExecutionInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  private static final Logger log = LoggerFactory.getLogger(TracingExecutionInterceptor.class);

  private final ContextStore<Object, String> responseQueueStore;

  public TracingExecutionInterceptor(ContextStore<Object, String> responseQueueStore) {
    this.responseQueueStore = responseQueueStore;
  }

  @Override
  public void beforeExecution(
      final BeforeExecution context, final ExecutionAttributes executionAttributes) {
    if (!AWS_LEGACY_TRACING && isPollingRequest(context.request())) {
      return; // SQS messages spans are created by aws-java-sqs-2.0
    }

    final AgentSpan span = startSpan("aws-sdk", DECORATE.spanName(executionAttributes));
    // TODO If DSM is enabled, add DSM context here too
    DECORATE.afterStart(span);
    executionAttributes.putAttribute(CONTEXT_ATTRIBUTE, span);
  }

  @Override
  public void afterMarshalling(
      final AfterMarshalling context, final ExecutionAttributes executionAttributes) {
    final Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    final AgentSpan span = fromContext(ddContext);
    if (context != null && span != null) {
      try (AgentScope ignored = activateSpan(span)) {
        DECORATE.onRequest(span, context.httpRequest());
        DECORATE.onSdkRequest(
            ddContext, context.request(), context.httpRequest(), executionAttributes);
      }
    }
  }

  @Override
  public SdkHttpRequest modifyHttpRequest(
      ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
    if (Config.get().isAwsPropagationEnabled()) {
      try {
        final Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
        if (ddContext != null) {
          SdkHttpRequest.Builder requestBuilder = context.httpRequest().toBuilder();
          Propagators.forConcern(XRAY_TRACING_CONCERN).inject(ddContext, requestBuilder, DECORATE);
          return requestBuilder.build();
        }
      } catch (Throwable e) {
        log.warn("Unable to inject trace header", e);
      }
    }
    return context.httpRequest();
  }

  @Override
  public void beforeTransmission(
      final BeforeTransmission context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span;
    if (!AWS_LEGACY_TRACING) {
      span = blackholeSpan();
    } else {
      final Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
      span = fromContext(ddContext);
    }
    if (span != null) {
      // This scope will be closed by AwsHttpClientInstrumentation since ExecutionInterceptor API
      // doesn't provide a way to run code in same thread after transmission has been scheduled.
      activateSpanWithoutScope(span);
    }
  }

  @Override
  public void afterExecution(
      final AfterExecution context, final ExecutionAttributes executionAttributes) {
    final Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    final AgentSpan span = fromContext(ddContext);
    if (span != null) {
      executionAttributes.putAttribute(CONTEXT_ATTRIBUTE, null);
      // Call onResponse on both types of responses:
      DECORATE.onSdkResponse(span, context.response(), context.httpResponse(), executionAttributes);
      DECORATE.onResponse(span, context.httpResponse());
      DECORATE.beforeFinish(span);
      span.finish();
    }
    if (!AWS_LEGACY_TRACING && isPollingResponse(context.response())) {
      // store queueUrl inside response for SqsReceiveResultInstrumentation
      context
          .request()
          .getValueForField("QueueUrl", String.class)
          .ifPresent(queueUrl -> responseQueueStore.put(context.response(), queueUrl));
    }
  }

  @Override
  public void onExecutionFailure(
      final FailedExecution context, final ExecutionAttributes executionAttributes) {
    final Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    final AgentSpan span = fromContext(ddContext);
    if (ddContext != null && span != null) {
      executionAttributes.putAttribute(CONTEXT_ATTRIBUTE, null);
      Optional<SdkResponse> responseOpt = context.response();
      if (responseOpt.isPresent()) {
        SdkResponse response = responseOpt.get();
        DECORATE.onSdkResponse(
            ddContext, response, response.sdkHttpResponse(), executionAttributes);
        DECORATE.onResponse(span, response.sdkHttpResponse());
        if (span.isError()) {
          DECORATE.onError(span, context.exception());
        }
      } else {
        DECORATE.onError(span, context.exception());
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  private static boolean isPollingRequest(SdkRequest request) {
    return null != request
        && "software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest"
            .equals(request.getClass().getName());
  }

  private static boolean isPollingResponse(SdkResponse response) {
    return null != response
        && "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse"
            .equals(response.getClass().getName());
  }

  public static void muzzleCheck() {
    // Noop
  }
}
