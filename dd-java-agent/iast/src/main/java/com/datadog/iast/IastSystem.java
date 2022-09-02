package com.datadog.iast;

import datadog.trace.api.Config;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.InstrumentationBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSystem {

  private static final Logger log = LoggerFactory.getLogger(IastSystem.class);

  public static void start(final SubscriptionService ss) {
    final Config config = Config.get();
    if (!config.isIastEnabled()) {
      log.debug("IAST is disabled");
      return;
    }
    log.debug("IAST is starting");

    final Reporter reporter = new Reporter();
    final IastModule iastModule = new IastModuleImpl(config, reporter);
    InstrumentationBridge.registerIastModule(iastModule);
    registerRequestStartedCallback(ss);
    registerRequestEndedCallback(ss);
  }

  private static void registerRequestStartedCallback(final SubscriptionService ss) {
    final EventType<Supplier<Flow<Object>>> event = Events.get().requestStarted();
    ss.registerCallback(event, () -> new Flow.ResultFlow<>(new IastRequestContext()));
  }

  private static void registerRequestEndedCallback(final SubscriptionService ss) {
    ss.registerCallback(
        Events.get().requestEnded(),
        (RequestContext ctx_, IGSpanInfo spanInfo) -> {
          TraceSegment traceSeg = ctx_.getTraceSegment();

          if (traceSeg != null) {
            traceSeg.setTagTop("_dd.iast.enabled", 1);
          }
          return Flow.ResultFlow.empty();
        });
  }
}
