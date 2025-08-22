package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpanWithoutScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.AWS_LEGACY_TRACING;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.DECORATE;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracing Request Handler */
public class TracingRequestHandler extends RequestHandler2 {

  public static final HandlerContextKey<Context> CONTEXT_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogContext"); // same as OnErrorDecorator.CONTEXT_CONTEXT_KEY

  private static final Logger log = LoggerFactory.getLogger(TracingRequestHandler.class);

  private final ContextStore<Object, String> responseQueueStore;
  private final ContextStore<AmazonWebServiceRequest, Context> requestContextStore;

  public TracingRequestHandler(
      ContextStore<Object, String> responseQueueStore,
      ContextStore<AmazonWebServiceRequest, Context> requestContextStore) {
    this.responseQueueStore = responseQueueStore;
    this.requestContextStore = requestContextStore;
  }

  @Override
  public void beforeRequest(final Request<?> request) {
    if (!AWS_LEGACY_TRACING && isPollingRequest(request.getOriginalRequest())) {
      // SQS messages spans are created by aws-java-sqs-1.0 - replace client scope with no-op,
      // so we can tell when receive call is complete without affecting the rest of the trace
      activateSpanWithoutScope(blackholeSpan());
    } else {
      Context context = requestContextStore.remove(request.getOriginalRequest());
      AgentSpan span = spanFromContext(context);
      if (span != null) {
        // we'll land here for SQS send requests when DSM is enabled. In that case, we create the
        // span in SqsInterceptor to inject DSM tags.
        span.setOperationName(AwsNameCache.spanName(request));
      } else {
        // this is the most common code path
        span = startSpan("aws-sdk", AwsNameCache.spanName(request));
        context = span; // TODO If DSM is enabled, add DSM context here too
      }
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      request.addHandlerContext(CONTEXT_CONTEXT_KEY, context);
      if (Config.get().isAwsPropagationEnabled()) {
        try {
          Propagators.forConcern(XRAY_TRACING_CONCERN).inject(span, request, DECORATE);
        } catch (Throwable e) {
          log.warn("Unable to inject trace header", e);
        }
      }
      // This scope will be closed by AwsHttpClientInstrumentation
      if (AWS_LEGACY_TRACING) {
        activateSpanWithoutScope(span);
      } else {
        activateSpanWithoutScope(blackholeSpan());
      }
    }
  }

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {
    final Context context = request.getHandlerContext(CONTEXT_CONTEXT_KEY);
    log.warn("context {}", context);
    AgentSpan span = null;
    if (context != null) {
      request.addHandlerContext(CONTEXT_CONTEXT_KEY, null);
      span = spanFromContext(context);
      if (span != null) {
        DECORATE.onResponse(span, response);
        DECORATE.onServiceResponse(span, request.getServiceName(), response);
        DECORATE.beforeFinish(span);
        span.finish();
      }
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
        && traceConfig().isDataStreamsEnabled()
        && "AmazonKinesis".equals(request.getServiceName())
        && "GetRecords".equals(requestAccess.getOperationNameFromType())) {
      String streamArn = requestAccess.getStreamARN(originalRequest);
      dsmCheckpoint(span, streamArn, response);
    }
  }

  private void dsmCheckpoint(AgentSpan span, String streamArn, Response<?> response) {
    if (null != streamArn) {
      List<?> records =
          GetterAccess.of(response.getAwsResponse()).getRecords(response.getAwsResponse());
      if (null != records) {
        DataStreamsTags tags = create("kinesis", INBOUND, streamArn);
        for (Object record : records) {
          Date arrivalTime = GetterAccess.of(record).getApproximateArrivalTimestamp(record);
          AgentDataStreamsMonitoring dataStreamsMonitoring =
              AgentTracer.get().getDataStreamsMonitoring();
          PathwayContext pathwayContext = dataStreamsMonitoring.newPathwayContext();
          DataStreamsContext dataStreamsContext = create(tags, arrivalTime.getTime(), 0);
          pathwayContext.setCheckpoint(dataStreamsContext, dataStreamsMonitoring::add);
          if (!span.context().getPathwayContext().isStarted()) {
            span.context().mergePathwayContext(pathwayContext);
          }
        }
      }
    }
  }

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {
    Context context = request.getHandlerContext(CONTEXT_CONTEXT_KEY);
    if (context == null) {
      // also try getting the context from the context store, if the error happened early
      context = requestContextStore.remove(request.getOriginalRequest());
    }

    if (context != null) {
      request.addHandlerContext(CONTEXT_CONTEXT_KEY, null);
      final AgentSpan span = spanFromContext(context);
      if (span != null) {
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
