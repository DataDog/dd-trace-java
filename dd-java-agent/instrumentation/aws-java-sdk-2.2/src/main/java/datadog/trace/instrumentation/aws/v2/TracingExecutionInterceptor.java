package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DSM_KEY;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.parseSqsUrl;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.AWS_LEGACY_TRACING;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/** AWS request execution interceptor */
public class TracingExecutionInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      new ExecutionAttribute<>("DatadogSpan");

  private static final Logger log = LoggerFactory.getLogger(TracingExecutionInterceptor.class);

  private final ContextStore<Object, String> responseQueueStore;

  public TracingExecutionInterceptor(ContextStore<Object, String> responseQueueStore) {
    this.responseQueueStore = responseQueueStore;
  }

  @Override
  public void beforeExecution(
      final Context.BeforeExecution context, final ExecutionAttributes executionAttributes) {
    if (!AWS_LEGACY_TRACING && isPollingRequest(context.request())) {
      return; // SQS messages spans are created by aws-java-sqs-2.0
    }

    final AgentSpan span = startSpan(DECORATE.spanName(executionAttributes));
    DECORATE.afterStart(span);
    executionAttributes.putAttribute(SPAN_ATTRIBUTE, span);
  }


  @Override
  public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (Config.get().isDataStreamsEnabled()) {
      AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
      if (context.request() instanceof SendMessageRequest) {
        SendMessageRequest request = (SendMessageRequest) context.request();
        String queueUrl = request.getValueForField("QueueUrl", String.class).get().toString();
        LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
        sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
        sortedTags.put(TOPIC_TAG, parseSqsUrl(queueUrl));
        sortedTags.put(TYPE_TAG, "sqs");

        String pathway = propagate().generatePathwayContext(span, sortedTags);

        String jsonPathway = String.format("{\"%s\": \"%s\"}", PROPAGATION_KEY_BASE64, pathway);
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>(request.messageAttributes());

        if (messageAttributes.size() < 10 && !messageAttributes.containsKey(DSM_KEY)) {
          messageAttributes.put(DSM_KEY, MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(jsonPathway)
              .build());
          return request.toBuilder().messageAttributes(messageAttributes).build();
        } else {
          return request;
        }

      } else if (context.request() instanceof SendMessageBatchRequest) {
        SendMessageBatchRequest request = (SendMessageBatchRequest) context.request();
        String queueUrl = request.getValueForField("QueueUrl", String.class).get().toString();
        LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
        sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
        sortedTags.put(TOPIC_TAG, parseSqsUrl(queueUrl));
        sortedTags.put(TYPE_TAG, "sqs");

        List<SendMessageBatchRequestEntry> entries = new ArrayList<>();

        String jsonPathway = "";
        for (SendMessageBatchRequestEntry entry: request.entries()) {
          String pathway = propagate().generatePathwayContext(span, sortedTags);
          Map<String, MessageAttributeValue> messageAttributes = new HashMap<>(entry.messageAttributes());
          jsonPathway = String.format("{\"%s\": \"%s\"}", PROPAGATION_KEY_BASE64, pathway);

          if (messageAttributes.size() < 10 && !messageAttributes.containsKey(DSM_KEY)) {
            messageAttributes.put(DSM_KEY, MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(jsonPathway)
                .build());
            entries.add(entry.toBuilder().messageAttributes(messageAttributes).build());
          } else {
            entries.add(entry.toBuilder().build());
          }
        }

        return request.toBuilder().entries(entries).build();

      } else if (context.request() instanceof ReceiveMessageRequest) {
        ReceiveMessageRequest request = (ReceiveMessageRequest) context.request();
        List<String> messageAttributeNames = new ArrayList<>(request.messageAttributeNames());
        if (messageAttributeNames.size() < 10 && !messageAttributeNames.contains(DSM_KEY)) {
          messageAttributeNames.add(DSM_KEY);
          return request.toBuilder().messageAttributeNames(messageAttributeNames).build();
        } else {
          return request;
        }
      } else {
        return context.request();
      }
    } else {
      return context.request();
    }

  }

  @Override
  public void afterMarshalling(
      final Context.AfterMarshalling context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      DECORATE.onRequest(span, context.httpRequest());
      DECORATE.onSdkRequest(span, context.request());
      DECORATE.onAttributes(span, executionAttributes);
    }
  }

  @Override
  public SdkHttpRequest modifyHttpRequest(
      Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
    if (Config.get().isAwsPropagationEnabled()) {
      try {
        final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        if (span != null) {
          SdkHttpRequest.Builder requestBuilder = context.httpRequest().toBuilder();
          propagate().inject(span, requestBuilder, DECORATE, TracePropagationStyle.XRAY);
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
      final Context.BeforeTransmission context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span;
    if (!AWS_LEGACY_TRACING && isPollingRequest(context.request())) {
      // SQS messages spans are created by aws-java-sqs-2.0 - replace client scope with no-op,
      // so we can tell when receive call is complete without affecting the rest of the trace
      span = noopSpan();
    } else {
      span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    }
    if (span != null) {
      // This scope will be closed by AwsHttpClientInstrumentation since ExecutionInterceptor API
      // doesn't provide a way to run code in same thread after transmission has been scheduled.
      activateSpan(span);
    }
  }

  @Override
  public void afterExecution(
      final Context.AfterExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, null);
      // Call onResponse on both types of responses:
      DECORATE.onResponse(span, context.response());
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
      final Context.FailedExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, null);
      DECORATE.onError(span, context.exception());
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
