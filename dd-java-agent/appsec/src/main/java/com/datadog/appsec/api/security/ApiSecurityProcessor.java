package com.datadog.appsec.api.security;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.report.AppSecEvent;
import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiSecurityProcessor implements TraceSegmentPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(ApiSecurityProcessor.class);
  private final ApiSecuritySampler sampler;
  private final EventProducerService producerService;

  public ApiSecurityProcessor(ApiSecuritySampler sampler, EventProducerService producerService) {
    this.sampler = sampler;
    this.producerService = producerService;
  }

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent> collectedEvents) {
    if (segment == null || ctx == null) {
      return;
    }
    if (!sampler.sample(ctx)) {
      log.debug("Request not sampled, skipping API security post-processing");
      return;
    }
    log.debug("Request sampled, processing API security post-processing");
    extractSchemas(ctx, segment);
  }

  private void extractSchemas(
      final @Nonnull AppSecRequestContext ctx, final @Nonnull TraceSegment traceSegment) {
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
      // TODO: Perhaps do this if schemas have actually been extracted (check when committing
      // derivatives)
      traceSegment.setTagTop(Tags.ASM_KEEP, true);
      if (!Config.get().isApmTracingEnabled()) {
        traceSegment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      }
    } catch (ExpiredSubscriberInfoException e) {
      log.debug("Subscriber info expired", e);
    }
  }
}
