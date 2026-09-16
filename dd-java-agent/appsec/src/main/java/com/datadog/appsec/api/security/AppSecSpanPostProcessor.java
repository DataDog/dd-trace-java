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
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSpanPostProcessor implements SpanPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(AppSecSpanPostProcessor.class);
  private static final String SCHEMA_DERIVATIVE_PREFIX = "_dd.appsec.s.";
  private final ApiSecuritySampler sampler;
  private final EventProducerService producerService;

  public AppSecSpanPostProcessor(ApiSecuritySampler sampler, EventProducerService producerService) {
    this.sampler = sampler;
    this.producerService = producerService;
  }

  @Override
  public void process(@Nonnull AgentSpan span, @Nonnull BooleanSupplier timeoutCheck) {
    final RequestContext ctx_ = span.getRequestContext();
    if (ctx_ == null) {
      return;
    }
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return;
    }

    if (!ctx.isKeepOpenForApiSecurityPostProcessing()) {
      return;
    }

    try {
      if (timeoutCheck.getAsBoolean()) {
        log.debug("Timeout detected, skipping API security post-processing");
        return;
      }
      if (!sampler.sampleRequest(ctx)) {
        log.debug("Request not sampled, skipping API security post-processing");
        return;
      }
      log.debug("Request sampled, processing API security post-processing");
      extractSchemas(ctx, ctx_.getTraceSegment(), ctx.getApiSecurityFramework());
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
    }
  }

  private void extractSchemas(
      final AppSecRequestContext ctx, final TraceSegment traceSegment, final String framework) {
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
      // NOTE: must be checked before committing derivatives, as that clears the derivatives map
      if (hasSchemaDerivative(ctx)) {
        WafMetricCollector.get().apiSecurityRequestSchema(framework);
      } else {
        WafMetricCollector.get().apiSecurityRequestNoSchema(framework);
      }
      ctx.commitDerivatives(traceSegment);
    } catch (ExpiredSubscriberInfoException e) {
      log.debug("Subscriber info expired", e);
    }
  }

  private static boolean hasSchemaDerivative(final AppSecRequestContext ctx) {
    for (String key : ctx.getDerivativeKeys()) {
      if (key != null && key.startsWith(SCHEMA_DERIVATIVE_PREFIX)) {
        return true;
      }
    }
    return false;
  }
}
