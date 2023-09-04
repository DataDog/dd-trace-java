package com.datadog.appsec.gateway;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.report.AppSecEventWrapper;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

class RequestEndedCallback implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  private final EventProducerService producerService;
  private final RateLimiter rateLimiter;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors;

  public RequestEndedCallback(
      final EventProducerService producerService,
      final RateLimiter rateLimiter,
      final List<TraceSegmentPostProcessor> traceSegmentPostProcessors) {
    this.producerService = producerService;
    this.rateLimiter = rateLimiter;
    this.traceSegmentPostProcessors = traceSegmentPostProcessors;
  }

  @Override
  public Flow<Void> apply(RequestContext ctx_, IGSpanInfo spanInfo) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    producerService.publishEvent(ctx, EventType.REQUEST_END);

    TraceSegment traceSeg = ctx_.getTraceSegment();

    // AppSec report metric and events for web span only
    if (traceSeg != null) {
      traceSeg.setTagTop("_dd.appsec.enabled", 1);
      traceSeg.setTagTop("_dd.runtime_family", "jvm");

      Collection<AppSecEvent100> collectedEvents = ctx.transferCollectedEvents();

      for (TraceSegmentPostProcessor pp : this.traceSegmentPostProcessors) {
        pp.processTraceSegment(traceSeg, ctx, collectedEvents);
      }

      // If detected any events - mark span at appsec.event
      if (!collectedEvents.isEmpty() && (rateLimiter == null || !rateLimiter.isThrottled())) {
        // Keep event related span, because it could be ignored in case of
        // reduced datadog sampling rate.
        traceSeg.setTagTop(DDTags.MANUAL_KEEP, true);
        traceSeg.setTagTop("appsec.event", true);
        traceSeg.setTagTop("network.client.ip", ctx.getPeerAddress());

        Map<String, List<String>> requestHeaders = ctx.getRequestHeaders();
        Map<String, List<String>> responseHeaders = ctx.getResponseHeaders();
        // Reflect client_ip as actor.ip for backward compatibility
        Object clientIp = spanInfo.getTags().get(Tags.HTTP_CLIENT_IP);
        if (clientIp != null) {
          traceSeg.setTagTop("actor.ip", clientIp);
        }

        // Report AppSec events via "_dd.appsec.json" tag
        AppSecEventWrapper wrapper = new AppSecEventWrapper(collectedEvents);
        traceSeg.setDataTop("appsec", wrapper);

        // Report collected request and response headers based on allow list
        if (requestHeaders != null) {
          requestHeaders.forEach(
              (name, value) -> {
                if (AppSecRequestContext.HEADERS_ALLOW_LIST.contains(name)) {
                  String v = Strings.join(",", value);
                  if (!v.isEmpty()) {
                    traceSeg.setTagTop("http.request.headers." + name, v);
                  }
                }
              });
        }
        if (responseHeaders != null) {
          responseHeaders.forEach(
              (name, value) -> {
                if (AppSecRequestContext.HEADERS_ALLOW_LIST.contains(name)) {
                  String v = String.join(",", value);
                  if (!v.isEmpty()) {
                    traceSeg.setTagTop("http.response.headers." + name, v);
                  }
                }
              });
        }
      }
    }

    ctx.close();
    return NoopFlow.INSTANCE;
  }
}
