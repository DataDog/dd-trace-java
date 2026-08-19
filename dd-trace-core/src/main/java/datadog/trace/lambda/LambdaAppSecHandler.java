package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.lambda.LambdaEventParser.MAX_EVENT_SIZE;
import static datadog.trace.lambda.LambdaEventParser.buildFullPath;
import static datadog.trace.lambda.LambdaEventParser.parseEvent;
import static datadog.trace.lambda.LambdaEventParser.parseJsonValue;
import static datadog.trace.lambda.LambdaEventParser.parseResponse;

import datadog.logging.RatelimitedLogger;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.lambda.LambdaEventParser.LambdaRequestData;
import datadog.trace.lambda.LambdaEventParser.LambdaResponseData;
import datadog.trace.lambda.LambdaEventParser.LambdaTriggerType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles AppSec processing for AWS Lambda invocations. Extracts Lambda event data and invokes
 * AppSec gateway callbacks.
 */
public class LambdaAppSecHandler {

  private static final Logger log = LoggerFactory.getLogger(LambdaAppSecHandler.class);
  private static final RatelimitedLogger rlLog = new RatelimitedLogger(log, 5, TimeUnit.MINUTES);

  // Carries the detected trigger type from processRequestStart to processResponseData within the
  // same Lambda invocation. Cleared in processRequestEnd.
  private static final ThreadLocal<LambdaTriggerType> CURRENT_TRIGGER_TYPE = new ThreadLocal<>();

  /**
   * Process AppSec request data at the start of a Lambda invocation. Extract event data and invokes
   * all relevant AppSec gateway callbacks.
   *
   * @param event the Lambda event object
   * @return AgentSpanContext containing AppSec data, or null if AppSec is disabled or processing
   *     fails
   */
  public static AgentSpanContext processRequestStart(Object event) {
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      log.debug("AppSec is not active, skipping request start processing");
      return null;
    }

    CURRENT_TRIGGER_TYPE.set(LambdaTriggerType.UNKNOWN);

    if (!(event instanceof ByteArrayInputStream)) {
      log.debug(
          "Event is not a ByteArrayInputStream, type: {}",
          event != null ? event.getClass().getName() : "null");
      return null;
    }

    try {
      LambdaRequestData eventData = parseEvent((ByteArrayInputStream) event);
      if (eventData == LambdaRequestData.EMPTY) {
        return null;
      }
      CURRENT_TRIGGER_TYPE.set(eventData.triggerType);
      return processAppSecRequestData(eventData);
    } catch (Exception e) {
      log.debug("Failed to process AppSec request data", e);
      return null;
    }
  }

  /**
   * Invokes the requestEnded gateway callback to add AppSec data to the span.
   *
   * @param span the current span
   */
  public static void processRequestEnd(AgentSpan span) {
    CURRENT_TRIGGER_TYPE.remove();

    if (!ActiveSubsystems.APPSEC_ACTIVE || span == null) {
      return;
    }

    RequestContext requestContext = span.getRequestContext();
    if (requestContext != null) {
      AgentTracer.TracerAPI tracer = AgentTracer.get();
      BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
          tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestEnded());
      if (requestEndedCallback != null) {
        requestEndedCallback.apply(requestContext, span);
      } else {
        log.debug("requestEnded callback is null");
      }

      // In Lambda, the WAF runs in processRequestStart before the span exists.
      // GatewayBridge propagates ASM_KEEP based on WAF attack events, but not on
      // isManuallyKept(), which is set by trace-tagging rules that produce no events.
      // Apply it here so those traces are not silently dropped.
      Object rawAppSecCtx = requestContext.getData(RequestContextSlot.APPSEC);
      AppSecContext appSecCtx =
          rawAppSecCtx instanceof AppSecContext ? (AppSecContext) rawAppSecCtx : null;
      if (appSecCtx != null && appSecCtx.isManuallyKept()) {
        TraceSegment traceSeg = requestContext.getTraceSegment();
        traceSeg.setTagTop(Tags.ASM_KEEP, true);
        traceSeg.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      }
    }
  }

  /**
   * Process response data through WAF before the request context is closed. Extracts status code,
   * headers, and body from the Lambda response and fires the corresponding gateway events.
   *
   * @param span the current span
   * @param result the Lambda handler result (expected to be a ByteArrayOutputStream)
   */
  public static void processResponseData(AgentSpan span, Object result) {
    if (!ActiveSubsystems.APPSEC_ACTIVE
        || span == null
        || !(result instanceof ByteArrayOutputStream)) {
      return;
    }

    try {
      byte[] bytes = ((ByteArrayOutputStream) result).toByteArray();
      if (bytes.length == 0 || bytes.length > MAX_EVENT_SIZE) {
        log.debug(
            "Response size {} exceeds limit {} or is empty, skipping response processing",
            bytes.length,
            MAX_EVENT_SIZE);
        return;
      }

      String json = new String(bytes, StandardCharsets.UTF_8);
      LambdaResponseData responseData = parseResponse(json);

      // Only process responses for known HTTP trigger types
      LambdaTriggerType triggerType = CURRENT_TRIGGER_TYPE.get();
      if (triggerType == null || !triggerType.isHttp()) {
        return;
      }

      if (responseData == null || responseData.statusCode == 0) {
        // No statusCode means this is not an API-GW formatted response, or JSON parsing failed.
        if (responseData == null || (responseData.headers.isEmpty() && responseData.body == null)) {
          // Parse failed or response has no API-GW structure (plain JSON body).
          // Treat the full response as the body
          Object fallbackBody;
          String fallbackContentType;
          try {
            fallbackBody = parseJsonValue(json);
            fallbackContentType = "application/json";
          } catch (Exception e) {
            fallbackBody = json;
            fallbackContentType = "text/plain";
          }
          Map<String, String> fallbackHeaders =
              Collections.singletonMap("content-type", fallbackContentType);
          responseData = new LambdaResponseData(0, fallbackHeaders, fallbackBody);
        }
        // else: responseData has explicit headers/body fields — keep them, just skip
        // responseStarted
        // (statusCode remains 0, so the responseStarted guard below will not fire).
      }

      RequestContext requestContext = span.getRequestContext();
      if (requestContext == null) {
        log.debug("Span has no RequestContext, skipping response processing");
        return;
      }

      AgentTracer.TracerAPI tracer = AgentTracer.get();
      CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.APPSEC);

      // Fire response gateway events. Flow results are intentionally ignored: blocking on response
      // is not supported for Lambda because remote config is unavailable in that environment.

      // Fire responseStarted
      if (responseData.statusCode > 0) {
        BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCb =
            cbp.getCallback(EVENTS.responseStarted());
        if (responseStartedCb != null) {
          responseStartedCb.apply(requestContext, responseData.statusCode);
        }
      }

      // Fire responseHeader for each allowed header
      if (responseData.headers != null && !responseData.headers.isEmpty()) {
        TriConsumer<RequestContext, String, String> responseHeaderCb =
            cbp.getCallback(EVENTS.responseHeader());
        if (responseHeaderCb != null) {
          for (Map.Entry<String, String> header : responseData.headers.entrySet()) {
            responseHeaderCb.accept(requestContext, header.getKey(), header.getValue());
          }
        }
      }

      // Fire responseHeaderDone
      Function<RequestContext, Flow<Void>> responseHeaderDoneCb =
          cbp.getCallback(EVENTS.responseHeaderDone());
      if (responseHeaderDoneCb != null) {
        responseHeaderDoneCb.apply(requestContext);
      }

      // Fire responseBody
      if (responseData.body != null) {
        BiFunction<RequestContext, Object, Flow<Void>> responseBodyCb =
            cbp.getCallback(EVENTS.responseBody());
        if (responseBodyCb != null) {
          responseBodyCb.apply(requestContext, responseData.body);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to process AppSec response data", e);
    }
  }

  /**
   * Merge AppSec context data into extension context.
   *
   * @param extensionContext context from extension
   * @param appSecContext context containing AppSec data
   * @return merged context
   */
  public static AgentSpanContext mergeContexts(
      AgentSpanContext extensionContext, AgentSpanContext appSecContext) {
    if (appSecContext == null) {
      return extensionContext;
    }
    if (extensionContext == null) {
      return appSecContext;
    }

    if (appSecContext instanceof TagContext) {
      TagContext extracted = (TagContext) appSecContext;
      Object appSecData = extracted.getRequestContextDataAppSec();

      if (extensionContext instanceof TagContext) {
        TagContext merged = (TagContext) extensionContext;
        if (appSecData != null) {
          merged.withRequestContextDataAppSec(appSecData);
        }
        return merged;
      }

      rlLog.warn(
          "Cannot merge AppSec data: extension context is not a TagContext: {}",
          extensionContext.getClass());
    }
    return extensionContext;
  }

  private static AgentSpanContext processAppSecRequestData(LambdaRequestData eventData) {
    AgentTracer.TracerAPI tracer = AgentTracer.get();
    Supplier<Flow<Object>> requestStartedCallback =
        tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestStarted());
    if (requestStartedCallback == null) {
      log.debug("requestStarted callback is null");
      return null;
    }

    TagContext tagContext = new TagContext();
    Object appSecRequestContext;

    // Call requestStarted
    appSecRequestContext = requestStartedCallback.get().getResult();
    tagContext.withRequestContextDataAppSec(appSecRequestContext);

    if (appSecRequestContext != null) {
      TemporaryRequestContext requestContext = new TemporaryRequestContext(appSecRequestContext);

      // Call requestMethodUriRaw
      if (eventData.method != null && eventData.path != null) {
        datadog.trace.api.function.TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>
            methodUriCallback =
                tracer
                    .getCallbackProvider(RequestContextSlot.APPSEC)
                    .getCallback(EVENTS.requestMethodUriRaw());
        if (methodUriCallback != null) {
          // Reconstruct full path with query string for AppSec analysis
          String fullPath = buildFullPath(eventData.path, eventData.queryParameters);
          LambdaURIDataAdapter uriAdapter = new LambdaURIDataAdapter(fullPath, eventData.headers);
          methodUriCallback.apply(requestContext, eventData.method, uriAdapter);
        } else {
          log.debug("requestMethodUriRaw callback is null");
        }
      }

      // Call requestHeader for each header
      if (eventData.headers != null && !eventData.headers.isEmpty()) {
        TriConsumer<RequestContext, String, String> headerCallback =
            tracer
                .getCallbackProvider(RequestContextSlot.APPSEC)
                .getCallback(EVENTS.requestHeader());
        if (headerCallback != null) {
          for (Map.Entry<String, String> header : eventData.headers.entrySet()) {
            headerCallback.accept(requestContext, header.getKey(), header.getValue());
          }
        } else {
          log.debug("requestHeader callback is null");
        }
      }

      // Call requestClientSocketAddress
      if (eventData.sourceIp != null) {
        datadog.trace.api.function.TriFunction<RequestContext, String, Integer, Flow<Void>>
            socketAddrCallback =
                tracer
                    .getCallbackProvider(RequestContextSlot.APPSEC)
                    .getCallback(EVENTS.requestClientSocketAddress());
        if (socketAddrCallback != null) {
          Integer port = eventData.sourcePort != null ? eventData.sourcePort : 0;
          socketAddrCallback.apply(requestContext, eventData.sourceIp, port);
        } else {
          log.debug("requestClientSocketAddress callback is null");
        }
      }

      // Call requestHeaderDone
      Function<RequestContext, Flow<Void>> headerDoneCallback =
          tracer
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.requestHeaderDone());
      if (headerDoneCallback != null) {
        headerDoneCallback.apply(requestContext);
      } else {
        log.debug("requestHeaderDone callback is null");
      }

      // Call requestPathParams
      if (eventData.pathParameters != null && !eventData.pathParameters.isEmpty()) {
        BiFunction<RequestContext, Map<String, ?>, Flow<Void>> pathParamsCallback =
            tracer
                .getCallbackProvider(RequestContextSlot.APPSEC)
                .getCallback(EVENTS.requestPathParams());
        if (pathParamsCallback != null) {
          pathParamsCallback.apply(requestContext, eventData.pathParameters);
        } else {
          log.debug("requestPathParams callback is null");
        }
      }

      // Call requestBodyProcessed
      if (eventData.body != null) {
        BiFunction<RequestContext, Object, Flow<Void>> bodyCallback =
            tracer
                .getCallbackProvider(RequestContextSlot.APPSEC)
                .getCallback(EVENTS.requestBodyProcessed());
        if (bodyCallback != null) {
          bodyCallback.apply(requestContext, eventData.body);
        } else {
          log.debug("requestBodyProcessed callback is null");
        }
      }
    }
    return tagContext;
  }

  /** Sets the current trigger type thread-local. Package-private for use in tests only. */
  static void setCurrentTriggerType(LambdaTriggerType type) {
    if (type == null) {
      CURRENT_TRIGGER_TYPE.remove();
    } else {
      CURRENT_TRIGGER_TYPE.set(type);
    }
  }

  /**
   * Temporary RequestContext implementation to hold AppSecRequestContext before a span is created.
   */
  private static class TemporaryRequestContext implements RequestContext {
    private final Object appSecRequestContext;

    TemporaryRequestContext(Object appSecRequestContext) {
      this.appSecRequestContext = appSecRequestContext;
    }

    @Override
    public <T> T getData(RequestContextSlot slot) {
      if (slot == RequestContextSlot.APPSEC) {
        return (T) appSecRequestContext;
      }
      return null;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return TraceSegment.NoOp.INSTANCE;
    }

    @Override
    public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {
      // No-op for temporary context
    }

    @Override
    public BlockResponseFunction getBlockResponseFunction() {
      return null;
    }

    @Override
    public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
      return null;
    }

    @Override
    public void setClientIpAddressData(ClientIpAddressData clientIpAddressData) {
      // No-op for temporary context
    }

    @Override
    public ClientIpAddressData getClientIpAddressData() {
      return null;
    }

    @Override
    public void close() {
      // No-op for temporary context
    }
  }
}
