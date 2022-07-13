package com.datadog.iast;

import datadog.trace.api.Config;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSystem {

  private static final Logger log = LoggerFactory.getLogger(IastSystem.class);

  public static void start(final InstrumentationGateway ig) {
    final Config config = Config.get();
    if (!config.isIastEnabled()) {
      log.debug("IAST is disabled");
      return;
    }
    log.debug("IAST is starting");

    registerRequestStartedCallback(ig);
    registerRequestEndedCallback(ig);
  }

  private static void registerRequestStartedCallback(final InstrumentationGateway ig) {
    final EventType<Supplier<Flow<RequestContext<Object>>>> event = Events.get().requestStarted();
    final Supplier<Flow<RequestContext<Object>>> prevCallback = ig.getCallback(event);
    if (prevCallback == null) {
      ig.registerCallback(
          event,
          () -> new Flow.ResultFlow<>(new RequestContextHolder(null, new IastRequestContext())));
    } else {
      throw new UnsupportedOperationException("TODO");
    }
  }

  private static void registerRequestEndedCallback(final InstrumentationGateway ig) {
    final EventType<BiFunction<RequestContext<Object>, IGSpanInfo, Flow<Void>>> event =
        Events.get().requestEnded();
    final BiFunction<RequestContext<Object>, IGSpanInfo, Flow<Void>> prevCallback =
        ig.getCallback(event);
    if (prevCallback == null) {
      ig.registerCallback(
          event,
          (RequestContext<Object> ctx, IGSpanInfo span) -> {
            Reporter.finalizeReports(ctx);
            return Flow.ResultFlow.empty();
          });
    } else {
      throw new UnsupportedOperationException("TODO");
    }
  }
}
