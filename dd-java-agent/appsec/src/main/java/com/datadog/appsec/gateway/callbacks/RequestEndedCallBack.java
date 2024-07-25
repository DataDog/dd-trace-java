package com.datadog.appsec.gateway.callbacks;

import static com.datadog.appsec.gateway.AppSecRequestContext.DEFAULT_REQUEST_HEADERS_ALLOW_LIST;
import static com.datadog.appsec.gateway.AppSecRequestContext.REQUEST_HEADERS_ALLOW_LIST;
import static com.datadog.appsec.gateway.AppSecRequestContext.RESPONSE_HEADERS_ALLOW_LIST;

import com.datadog.appsec.api.security.ApiSecurityRequestSampler;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.report.AppSecEventWrapper;
import com.datadog.appsec.stack_trace.StackTraceCollection;
import com.datadog.appsec.util.ObjectFlattener;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestEndedCallBack implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  /** User tracking tags that will force the collection of request headers */
  private static final String[] USER_TRACKING_TAGS = {
    "appsec.events.users.login.success.track", "appsec.events.users.login.failure.track"
  };

  private static final Logger log = LoggerFactory.getLogger(RequestEndedCallBack.class);

  private final ApiSecurityRequestSampler requestSampler;
  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors;

  public RequestEndedCallBack(
      ApiSecurityRequestSampler requestSampler,
      SubscribersCache subscribersCache,
      EventProducerService producerService,
      List<TraceSegmentPostProcessor> traceSegmentPostProcessors) {
    this.requestSampler = requestSampler;
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
    this.traceSegmentPostProcessors = traceSegmentPostProcessors;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, IGSpanInfo spanInfo) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    CallbackUtils.INSTANCE.maybeExtractSchemas(
        ctx, requestSampler, subscribersCache, producerService);

    // WAF call
    ctx.closeAdditive();

    TraceSegment traceSeg = requestContext.getTraceSegment();

    // AppSec report metric and events for web span only
    if (traceSeg != null) {
      traceSeg.setTagTop("_dd.appsec.enabled", 1);
      traceSeg.setTagTop("_dd.runtime_family", "jvm");

      Collection<AppSecEvent> collectedEvents = ctx.transferCollectedEvents();

      for (TraceSegmentPostProcessor pp : traceSegmentPostProcessors) {
        pp.processTraceSegment(traceSeg, ctx, collectedEvents);
      }

      // If detected any events - mark span at appsec.event
      if (!collectedEvents.isEmpty()) {
        // Set asm keep in case that root span was not available when events are detected
        traceSeg.setTagTop(Tags.ASM_KEEP, true);
        traceSeg.setTagTop(Tags.PROPAGATED_APPSEC, true);
        traceSeg.setTagTop("appsec.event", true);
        traceSeg.setTagTop("network.client.ip", ctx.getPeerAddress());

        // Reflect client_ip as actor.ip for backward compatibility
        Object clientIp = spanInfo.getTags().get(Tags.HTTP_CLIENT_IP);
        if (clientIp != null) {
          traceSeg.setTagTop("actor.ip", clientIp);
        }

        // Report AppSec events via "_dd.appsec.json" tag
        AppSecEventWrapper wrapper = new AppSecEventWrapper(collectedEvents);
        traceSeg.setDataTop("appsec", wrapper);

        // Report collected request and response headers based on allow list
        writeRequestHeaders(traceSeg, REQUEST_HEADERS_ALLOW_LIST, ctx.getRequestHeaders());
        writeResponseHeaders(traceSeg, RESPONSE_HEADERS_ALLOW_LIST, ctx.getResponseHeaders());

        // Report collected stack traces
        StackTraceCollection stackTraceCollection = ctx.transferStackTracesCollection();
        if (stackTraceCollection != null) {
          Object flatStruct = ObjectFlattener.flatten(stackTraceCollection);
          if (flatStruct != null) {
            traceSeg.setMetaStructTop("_dd.stack", flatStruct);
          }
        }
      } else if (hasUserTrackingEvent(traceSeg)) {
        // Report all collected request headers on user tracking event
        writeRequestHeaders(traceSeg, REQUEST_HEADERS_ALLOW_LIST, ctx.getRequestHeaders());
      } else {
        // Report minimum set of collected request headers
        writeRequestHeaders(traceSeg, DEFAULT_REQUEST_HEADERS_ALLOW_LIST, ctx.getRequestHeaders());
      }
      // If extracted any Api Schemas - commit them
      if (!ctx.commitApiSchemas(traceSeg)) {
        log.debug("Unable to commit, api security schemas and will be skipped");
      }

      if (ctx.isBlocked()) {
        WafMetricCollector.get().wafRequestBlocked();
      } else if (!collectedEvents.isEmpty()) {
        WafMetricCollector.get().wafRequestTriggered();
      } else {
        WafMetricCollector.get().wafRequest();
      }
    }

    ctx.close();
    return NoopFlow.INSTANCE;
  }

  private static boolean hasUserTrackingEvent(final TraceSegment traceSeg) {
    for (String tagName : USER_TRACKING_TAGS) {
      final Object value = traceSeg.getTagTop(tagName);
      if (value != null && "true".equalsIgnoreCase(value.toString())) {
        return true;
      }
    }
    return false;
  }

  private static void writeRequestHeaders(
      final TraceSegment traceSeg,
      final Set<String> allowed,
      final Map<String, List<String>> headers) {
    writeHeaders(traceSeg, "http.request.headers.", allowed, headers);
  }

  private static void writeResponseHeaders(
      final TraceSegment traceSeg,
      final Set<String> allowed,
      final Map<String, List<String>> headers) {
    writeHeaders(traceSeg, "http.response.headers.", allowed, headers);
  }

  private static void writeHeaders(
      final TraceSegment traceSeg,
      final String prefix,
      final Set<String> allowed,
      final Map<String, List<String>> headers) {
    if (headers != null) {
      headers.forEach(
          (name, value) -> {
            if (allowed.contains(name)) {
              String v = String.join(",", value);
              if (!v.isEmpty()) {
                traceSeg.setTagTop(prefix + name, v);
              }
            }
          });
    }
  }
}
