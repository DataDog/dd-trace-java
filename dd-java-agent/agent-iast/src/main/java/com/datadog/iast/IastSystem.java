package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.util.AgentTaskScheduler;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSystem {

  private static final Logger log = LoggerFactory.getLogger(IastSystem.class);

  public static void start(final SubscriptionService ss) {
    start(ss, null);
  }

  public static void start(final SubscriptionService ss, OverheadController overheadController) {
    final Config config = Config.get();
    if (!config.isIastEnabled()) {
      log.debug("IAST is disabled");
      return;
    }
    log.debug("IAST is starting");

    TaintedObjects.setDebug(config.isIastTaintTrackingDebugEnabled());
    final Reporter reporter = new Reporter(config);
    if (overheadController == null) {
      overheadController = new OverheadController(config, AgentTaskScheduler.INSTANCE);
    }
    final IastModule iastModule = new IastModuleImpl(config, reporter, overheadController);
    InstrumentationBridge.registerIastModule(iastModule);
    registerRequestStartedCallback(ss, overheadController);
    registerRequestEndedCallback(ss, overheadController);
    log.debug("IAST started");
  }

  private static void registerRequestStartedCallback(
      final SubscriptionService ss, final OverheadController overheadController) {
    final EventType<Supplier<Flow<Object>>> event = Events.get().requestStarted();
    ss.registerCallback(event, new RequestStartedHandler(overheadController));
  }

  private static void registerRequestEndedCallback(
      final SubscriptionService ss, final OverheadController overheadController) {
    final EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> event =
        Events.get().requestEnded();
    ss.registerCallback(event, new RequestEndedHandler(overheadController));
  }
}
