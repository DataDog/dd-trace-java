package com.datadog.appsec.api.security;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor;

import java.util.Collections;
import java.util.function.BooleanSupplier;

public class AppSecSpanPostProcessor implements SpanPostProcessor {

  @Override
  public void process(AgentSpan span, BooleanSupplier timeoutCheck) {
    if (timeoutCheck.getAsBoolean()) {
      return;
    }
    final RequestContext ctx = span.getRequestContext();
    if (ctx == null) {
      return;
    }
    final AppSecRequestContext appsecCtx = ctx.getData(RequestContextSlot.APPSEC);
    if (appsecCtx == null) {
      return;
    }

    maybeExtractSchemas(appsecCtx);
    ctx.close();
    // Decrease the counter to allow the next request to be post-processed
    postProcessingCounter.release();
  }

  private void maybeExtractSchemas(AppSecRequestContext ctx) {
    boolean extractSchema = false;
    if (Config.get().isApiSecurityEnabled() && requestSampler != null) {
      extractSchema = requestSampler.sampleRequest(ctx);
    }

    if (!extractSchema) {
      return;
    }

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = requestEndSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.WAF_CONTEXT_PROCESSOR);
        requestEndSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return;
      }

      DataBundle bundle =
          new SingletonDataBundle<>(
              KnownAddresses.WAF_CONTEXT_PROCESSOR,
              Collections.singletonMap("extract-schema", true));
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
        return;
      } catch (ExpiredSubscriberInfoException e) {
        requestEndSubInfo = null;
      }
    }
  }

}
