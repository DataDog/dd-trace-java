package com.datadog.iast;

import com.datadog.iast.HasDependencies.Dependencies;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.propagation.FastCodecModule;
import com.datadog.iast.propagation.PropagationModuleImpl;
import com.datadog.iast.propagation.StringModuleImpl;
import com.datadog.iast.sink.CommandInjectionModuleImpl;
import com.datadog.iast.sink.InsecureCookieModuleImpl;
import com.datadog.iast.sink.LdapInjectionModuleImpl;
import com.datadog.iast.sink.PathTraversalModuleImpl;
import com.datadog.iast.sink.SqlInjectionModuleImpl;
import com.datadog.iast.sink.SsrfModuleImpl;
import com.datadog.iast.sink.WeakCipherModuleImpl;
import com.datadog.iast.sink.WeakHashModuleImpl;
import com.datadog.iast.source.WebModuleImpl;
import com.datadog.iast.telemetry.IastTelemetry;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastSystem.class);
  public static boolean DEBUG = false;

  public static void start(final SubscriptionService ss) {
    start(ss, null, null);
  }

  public static void start(
      final SubscriptionService ss,
      OverheadController overheadController,
      IastTelemetry telemetry) {
    final Config config = Config.get();
    if (config.getIastActivation() != ProductActivation.FULLY_ENABLED) {
      LOGGER.debug("IAST is disabled");
      return;
    }
    DEBUG = config.isIastDebugEnabled();
    LOGGER.debug("IAST is starting: debug={}", DEBUG);
    final Reporter reporter = new Reporter(config, AgentTaskScheduler.INSTANCE);
    if (overheadController == null) {
      overheadController = OverheadController.build(config, AgentTaskScheduler.INSTANCE);
    }
    if (telemetry == null) {
      telemetry = IastTelemetry.build(config);
    }
    final Dependencies dependencies =
        new Dependencies(
            config, reporter, overheadController, telemetry, StackWalkerFactory.INSTANCE);
    iastModules().forEach(registerModule(dependencies));
    registerRequestStartedCallback(ss, dependencies);
    registerRequestEndedCallback(ss, dependencies);
    LOGGER.debug("IAST started");
  }

  private static Consumer<IastModule> registerModule(final Dependencies dependencies) {
    return module -> {
      if (module instanceof HasDependencies) {
        ((HasDependencies) module).registerDependencies(dependencies);
      }
      InstrumentationBridge.registerIastModule(module);
    };
  }

  private static Stream<IastModule> iastModules() {
    return Stream.of(
        new WebModuleImpl(),
        new StringModuleImpl(),
        new FastCodecModule(),
        new SqlInjectionModuleImpl(),
        new PathTraversalModuleImpl(),
        new CommandInjectionModuleImpl(),
        new WeakCipherModuleImpl(),
        new WeakHashModuleImpl(),
        new LdapInjectionModuleImpl(),
        new PropagationModuleImpl(),
        new InsecureCookieModuleImpl(),
        new SsrfModuleImpl());
  }

  private static void registerRequestStartedCallback(
      final SubscriptionService ss, final Dependencies dependencies) {
    final EventType<Supplier<Flow<Object>>> event = Events.get().requestStarted();
    ss.registerCallback(event, new RequestStartedHandler(dependencies));
  }

  private static void registerRequestEndedCallback(
      final SubscriptionService ss, final Dependencies dependencies) {
    final EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> event =
        Events.get().requestEnded();
    ss.registerCallback(event, new RequestEndedHandler(dependencies));
  }
}
