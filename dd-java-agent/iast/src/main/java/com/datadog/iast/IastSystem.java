package com.datadog.iast;

import datadog.trace.api.Config;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
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

    registerRequestStartedCallback(ss);
    registerRequestEndedCallback(ss);
  }

  private static void registerRequestStartedCallback(final SubscriptionService ss) {
    final EventType<Supplier<Flow<Object>>> event = Events.get().requestStarted();
    ss.registerCallback(event, () -> new Flow.ResultFlow<>(new IastRequestContext()));
  }

  private static void registerRequestEndedCallback(final SubscriptionService ss) {
    final EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> event =
        Events.get().requestEnded();
    ss.registerCallback(
        event,
        (RequestContext ctx, IGSpanInfo span) -> {
          Reporter.finalizeReports(ctx);
          return Flow.ResultFlow.empty();
        });
  }
}
