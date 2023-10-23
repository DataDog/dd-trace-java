package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_IN;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.AWS_LEGACY_TRACING;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.DECORATE;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.api.Config;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracing Request Handler */
public class TracingRequestHandler extends RequestHandler2 {

  public static final HandlerContextKey<AgentSpan> SPAN_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogSpan"); // same as OnErrorDecorator.SPAN_CONTEXT_KEY

  private static final Logger log = LoggerFactory.getLogger(TracingRequestHandler.class);

  private final ContextStore<Object, String> responseQueueStore;

  public TracingRequestHandler(ContextStore<Object, String> responseQueueStore) {
    this.responseQueueStore = responseQueueStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(final AmazonWebServiceRequest request) {
    return request;
  }

  @Override
  public void beforeRequest(final Request<?> request) {
    final AgentSpan span;
    if (!AWS_LEGACY_TRACING && isPollingRequest(request.getOriginalRequest())) {
      // SQS messages spans are created by aws-java-sqs-1.0 - replace client scope with no-op,
      // so we can tell when receive call is complete without affecting the rest of the trace
      span = noopSpan();
    } else {
      span = startSpan(AwsNameCache.spanName(request));
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      request.addHandlerContext(SPAN_CONTEXT_KEY, span);
      if (Config.get().isAwsPropagationEnabled()) {
        try {
          propagate().inject(span, request, DECORATE, TracePropagationStyle.XRAY);
        } catch (Throwable e) {
          log.warn("Unable to inject trace header", e);
        }
      }
    }

    // This scope will be closed by AwsHttpClientInstrumentation
    activateSpan(span);
  }

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {
    final AgentSpan span = request.getHandlerContext(SPAN_CONTEXT_KEY);
    if (span != null) {
      request.addHandlerContext(SPAN_CONTEXT_KEY, null);
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      span.finish();
    }
    AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
    GetterAccess requestAccess = GetterAccess.of(originalRequest);
    if (!AWS_LEGACY_TRACING && isPollingResponse(response.getAwsResponse())) {
      try {
        // store queueUrl inside response for SqsReceiveResultInstrumentation
        responseQueueStore.put(
            response.getAwsResponse(), requestAccess.getQueueUrl(originalRequest));
      } catch (Throwable e) {
        log.debug("Unable to extract queueUrl from ReceiveMessageRequest", e);
      }
    }
    if (span != null
        && span.traceConfig().isDataStreamsEnabled()
        && "AmazonKinesis".equals(request.getServiceName())
        && "GetRecords".equals(requestAccess.getOperationNameFromType())) {
      String streamArn = requestAccess.getStreamARN(originalRequest);
      if (null != streamArn) {
        List records =
            GetterAccess.of(response.getAwsResponse()).getRecords(response.getAwsResponse());
        if (null != records) {
          LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
          sortedTags.put(DIRECTION_TAG, DIRECTION_IN);
          sortedTags.put(TOPIC_TAG, streamArn);
          sortedTags.put(TYPE_TAG, "kinesis");
          for (Object record : records) {
            Date arrivalTime = GetterAccess.of(record).getApproximateArrivalTimestamp(record);
            AgentDataStreamsMonitoring dataStreamsMonitoring =
                AgentTracer.get().getDataStreamsMonitoring();
            PathwayContext pathwayContext = dataStreamsMonitoring.newPathwayContext();
            pathwayContext.setCheckpoint(
                sortedTags, dataStreamsMonitoring::add, arrivalTime.getTime());
            if (!span.context().getPathwayContext().isStarted()) {
              span.context().mergePathwayContext(pathwayContext);
            }
          }
        }
      }
    }
  }

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {
    final AgentSpan span = request.getHandlerContext(SPAN_CONTEXT_KEY);
    if (span != null) {
      request.addHandlerContext(SPAN_CONTEXT_KEY, null);
      if (response != null) {
        DECORATE.onResponse(span, response);
        if (span.isError()) {
          DECORATE.onError(span, e);
        }
      } else {
        DECORATE.onError(span, e);
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  private static boolean isPollingRequest(AmazonWebServiceRequest request) {
    return null != request
        && "com.amazonaws.services.sqs.model.ReceiveMessageRequest"
            .equals(request.getClass().getName());
  }

  private static boolean isPollingResponse(Object response) {
    return null != response
        && "com.amazonaws.services.sqs.model.ReceiveMessageResult"
            .equals(response.getClass().getName());
  }
}
