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
    AppSecRequestContext ctx = null;
    RequestContext ctx_ = null;
    boolean needsRelease = false;

    try {
      ctx_ = span.getRequestContext();
      if (ctx_ == null) {
        return;
      }
      ctx = ctx_.getData(RequestContextSlot.APPSEC);
      if (ctx == null) {
        return;
      }

      // Check if we acquired a permit for this request - must be inside try to ensure finally runs
      needsRelease = ctx.isKeepOpenForApiSecurityPostProcessing();
      if (!needsRelease) {
        return;
      }

      if (timeoutCheck.getAsBoolean()) {
        log.debug("Timeout detected, skipping API security post-processing");
        return;
      }
      if (!sampler.sampleRequest(ctx)) {
        log.debug("Request not sampled, skipping API security post-processing");
        return;
      }
      log.debug("Request sampled, processing API security post-processing");
      extractSchemas(ctx, ctx_.getTraceSegment());
    } finally {
      // Always release the semaphore permit if we acquired one
      if (needsRelease) {
        if (ctx != null) {
          ctx.setKeepOpenForApiSecurityPostProcessing(false);
          // XXX: Close the additive first. This is not strictly needed, but it'll prevent getting
          // it detected as a missed request-ended event.
          try {
            ctx.closeWafContext();
          } catch (Exception e) {
            log.debug("Error closing WAF context", e);
          }
          try {
            ctx.close();
          } catch (Exception e) {
            log.debug("Error closing AppSecRequestContext", e);
          }
        }
        sampler.releaseOne();
      }
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
}
