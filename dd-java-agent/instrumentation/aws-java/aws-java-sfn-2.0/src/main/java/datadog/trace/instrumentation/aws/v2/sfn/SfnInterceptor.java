package datadog.trace.instrumentation.aws.v2.sfn;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context.ModifyRequest;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartSyncExecutionRequest;

public class SfnInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  public SfnInterceptor() {}

  @Override
  public SdkRequest modifyRequest(ModifyRequest context, ExecutionAttributes executionAttributes) {
    SdkRequest request = context.request();
    if (!Config.get().isAwsInjectDatadogAttributeEnabled()) {
      return request;
    }
    try {
      return modifyRequestImpl(context, executionAttributes);
    } catch (Exception e) {
      return request;
    }
  }

  public SdkRequest modifyRequestImpl(
      ModifyRequest context, ExecutionAttributes executionAttributes) {
    final Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    final AgentSpan span = fromContext(ddContext);
    // StartExecutionRequest
    if (context.request() instanceof StartExecutionRequest) {
      StartExecutionRequest request = (StartExecutionRequest) context.request();
      if (request.input() == null) {
        return request;
      }
      return injectTraceContext(span, request);
    }

    // StartSyncExecutionRequest
    if (context.request() instanceof StartSyncExecutionRequest) {
      StartSyncExecutionRequest request = (StartSyncExecutionRequest) context.request();
      if (request.input() == null) {
        return request;
      }
      return injectTraceContext(span, request);
    }

    return context.request();
  }

  private SdkRequest injectTraceContext(AgentSpan span, StartExecutionRequest request) {
    String ddTraceContextJSON = InputAttributeInjector.buildTraceContext(span);
    // Inject the trace context into the StartExecutionRequest input
    String modifiedInput =
        InputAttributeInjector.getModifiedInput(request.input(), ddTraceContextJSON);

    return request.toBuilder().input(modifiedInput).build();
  }

  private SdkRequest injectTraceContext(AgentSpan span, StartSyncExecutionRequest request) {
    String ddTraceContextJSON = InputAttributeInjector.buildTraceContext(span);
    // Inject the trace context into the StartSyncExecutionRequest input
    String modifiedInput =
        InputAttributeInjector.getModifiedInput(request.input(), ddTraceContextJSON);

    return request.toBuilder().input(modifiedInput).build();
  }
}
