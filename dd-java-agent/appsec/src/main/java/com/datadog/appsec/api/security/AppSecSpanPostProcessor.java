package com.datadog.appsec.api.security;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSpanPostProcessor implements SpanPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(AppSecSpanPostProcessor.class);
  private final ApiSecuritySampler sampler;
  private final EventProducerService producerService;

  public AppSecSpanPostProcessor(ApiSecuritySampler sampler, EventProducerService producerService) {
    this.sampler = sampler;
    this.producerService = producerService;
  }

  @Override
  public void process(@Nonnull AgentSpan span, @Nonnull BooleanSupplier timeoutCheck) {
    long timestamp = System.currentTimeMillis();
    String traceId = span.getTraceId() != null ? span.getTraceId().toString() : "null";
    String spanId = String.valueOf(span.getSpanId());

    final RequestContext ctx_ = span.getRequestContext();
    if (ctx_ == null) {
      logProcessingDecision(timestamp, traceId, spanId, false, "no request context", "start");
      return;
    }
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      logProcessingDecision(timestamp, traceId, spanId, false, "no appsec context", "start");
      return;
    }

    if (!ctx.isKeepOpenForApiSecurityPostProcessing()) {
      logProcessingDecision(
          timestamp, traceId, spanId, false, "not marked for post-processing", "start");
      return;
    }

    try {
      if (timeoutCheck.getAsBoolean()) {
        log.debug("Timeout detected, skipping API security post-processing");
        logProcessingDecision(
            timestamp, traceId, spanId, false, "timeout detected", "pre-sampling");
        return;
      }
      if (!sampler.sampleRequest(ctx)) {
        log.debug("Request not sampled, skipping API security post-processing");
        logProcessingDecision(
            timestamp, traceId, spanId, false, "request not sampled", "post-sampling");
        return;
      }
      log.debug("Request sampled, processing API security post-processing");
      logProcessingDecision(
          timestamp, traceId, spanId, true, "sampled, extracting schemas", "extracting");
      extractSchemas(ctx, ctx_.getTraceSegment());
      logProcessingDecision(timestamp, traceId, spanId, true, "extraction completed", "completed");
    } finally {
      ctx.setKeepOpenForApiSecurityPostProcessing(false);
      try {
        // XXX: Close the additive first. This is not strictly needed, but it'll prevent getting it
        // detected as a
        // missed request-ended event.
        ctx.closeWafContext();
        ctx.close();
      } catch (Exception e) {
        log.debug("Error closing AppSecRequestContext", e);
      }
      sampler.releaseOne();
      logProcessingDecision(timestamp, traceId, spanId, false, "cleanup completed", "cleanup");
    }
  }

  private void extractSchemas(final AppSecRequestContext ctx, final TraceSegment traceSegment) {
    final EventProducerService.DataSubscriberInfo sub =
        producerService.getDataSubscribers(KnownAddresses.WAF_CONTEXT_PROCESSOR);
    if (sub == null || sub.isEmpty()) {
      log.debug("No subscribers for schema extraction");
      return;
    }

    final DataBundle bundle =
        new SingletonDataBundle<>(
            KnownAddresses.WAF_CONTEXT_PROCESSOR, Collections.singletonMap("extract-schema", true));
    try {
      GatewayContext gwCtx = new GatewayContext(false);
      producerService.publishDataEvent(sub, ctx, bundle, gwCtx);
      ctx.commitDerivatives(traceSegment);
    } catch (ExpiredSubscriberInfoException e) {
      log.debug("Subscriber info expired", e);
    }
  }

  private void logProcessingDecision(
      long timestamp,
      String traceId,
      String spanId,
      boolean processed,
      String reason,
      String stage) {
    log.info(
        "[APPSEC_SPAN_POST_PROCESSING] timestamp={}, traceId={}, spanId={}, processed={}, reason={}, stage={}",
        timestamp,
        traceId,
        spanId,
        processed,
        reason,
        stage);
  }
}
