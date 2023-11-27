package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.propagation.FastCodecModule;
import com.datadog.iast.propagation.PropagationModuleImpl;
import com.datadog.iast.propagation.StringModuleImpl;
import com.datadog.iast.sink.CommandInjectionModuleImpl;
import com.datadog.iast.sink.HstsMissingHeaderModuleImpl;
import com.datadog.iast.sink.HttpResponseHeaderModuleImpl;
import com.datadog.iast.sink.InsecureCookieModuleImpl;
import com.datadog.iast.sink.LdapInjectionModuleImpl;
import com.datadog.iast.sink.NoHttpOnlyCookieModuleImpl;
import com.datadog.iast.sink.NoSameSiteCookieModuleImpl;
import com.datadog.iast.sink.PathTraversalModuleImpl;
import com.datadog.iast.sink.SqlInjectionModuleImpl;
import com.datadog.iast.sink.SsrfModuleImpl;
import com.datadog.iast.sink.StacktraceLeakModuleImpl;
import com.datadog.iast.sink.TrustBoundaryViolationModuleImpl;
import com.datadog.iast.sink.UnvalidatedRedirectModuleImpl;
import com.datadog.iast.sink.WeakCipherModuleImpl;
import com.datadog.iast.sink.WeakHashModuleImpl;
import com.datadog.iast.sink.WeakRandomnessModuleImpl;
import com.datadog.iast.sink.XContentTypeModuleImpl;
import com.datadog.iast.sink.XPathInjectionModuleImpl;
import com.datadog.iast.sink.XssModuleImpl;
import com.datadog.iast.telemetry.TelemetryRequestEndedHandler;
import com.datadog.iast.telemetry.TelemetryRequestStartedHandler;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.Subsystem;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;

import java.lang.instrument.Instrumentation;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSystem implements Subsystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastSystem.class);
  public static boolean DEBUG = false;

  @Override
  public void maybeStart(final Instrumentation inst, final Object sco) {
    if (Config.get().getIastActivation() != ProductActivation.FULLY_ENABLED) {
      LOGGER.debug("IAST is disabled");
      return;
    }
    try {
      final SubscriptionService ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.IAST);
      start(ss);
    } catch (final Throwable t) {
      LOGGER.error("Error starting IAST subsystem", t);
    }
  }

  public static void start(final SubscriptionService ss) {
    start(ss, null);
  }

  public static void start(
      final SubscriptionService ss, @Nullable OverheadController overheadController) {
    final Config config = Config.get();
    DEBUG = config.isIastDebugEnabled();
    LOGGER.debug("IAST is starting: debug={}", DEBUG);
    final Reporter reporter = new Reporter(config, AgentTaskScheduler.INSTANCE);
    if (overheadController == null) {
      overheadController = OverheadController.build(config, AgentTaskScheduler.INSTANCE);
    }
    final Dependencies dependencies =
        new Dependencies(config, reporter, overheadController, StackWalkerFactory.INSTANCE);
    final boolean addTelemetry = config.getIastTelemetryVerbosity() != Verbosity.OFF;
    iastModules(dependencies).forEach(InstrumentationBridge::registerIastModule);
    registerRequestStartedCallback(ss, addTelemetry, dependencies);
    registerRequestEndedCallback(ss, addTelemetry, dependencies);
    registerHeadersCallback(ss);
    registerGrpcServerRequestMessageCallback(ss);
    LOGGER.debug("IAST started");
  }

  private static Stream<IastModule> iastModules(final Dependencies dependencies) {
    return Stream.of(
        new StringModuleImpl(),
        new FastCodecModule(),
        new SqlInjectionModuleImpl(dependencies),
        new PathTraversalModuleImpl(dependencies),
        new CommandInjectionModuleImpl(dependencies),
        new WeakCipherModuleImpl(dependencies),
        new WeakHashModuleImpl(dependencies),
        new LdapInjectionModuleImpl(dependencies),
        new PropagationModuleImpl(),
        new HttpResponseHeaderModuleImpl(dependencies),
        new HstsMissingHeaderModuleImpl(dependencies),
        new InsecureCookieModuleImpl(),
        new NoHttpOnlyCookieModuleImpl(),
        new XContentTypeModuleImpl(dependencies),
        new NoSameSiteCookieModuleImpl(),
        new SsrfModuleImpl(dependencies),
        new UnvalidatedRedirectModuleImpl(dependencies),
        new WeakRandomnessModuleImpl(dependencies),
        new XPathInjectionModuleImpl(dependencies),
        new TrustBoundaryViolationModuleImpl(dependencies),
        new XssModuleImpl(dependencies),
        new StacktraceLeakModuleImpl(dependencies));
  }

  private static void registerRequestStartedCallback(
      final SubscriptionService ss, final boolean addTelemetry, final Dependencies dependencies) {
    final EventType<Supplier<Flow<Object>>> event = Events.get().requestStarted();
    final Supplier<Flow<Object>> handler =
        addTelemetry
            ? new TelemetryRequestStartedHandler(dependencies)
            : new RequestStartedHandler(dependencies);
    ss.registerCallback(event, handler);
  }

  private static void registerRequestEndedCallback(
      final SubscriptionService ss, final boolean addTelemetry, final Dependencies dependencies) {
    final EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> event =
        Events.get().requestEnded();
    final RequestEndedHandler handler = new RequestEndedHandler(dependencies);
    ss.registerCallback(event, addTelemetry ? new TelemetryRequestEndedHandler(handler) : handler);
  }

  private static void registerHeadersCallback(final SubscriptionService ss) {
    final EventType<TriConsumer<RequestContext, String, String>> event =
        Events.get().requestHeader();
    final TriConsumer<RequestContext, String, String> handler = new RequestHeaderHandler();
    ss.registerCallback(event, handler);
  }

  private static void registerGrpcServerRequestMessageCallback(final SubscriptionService ss) {
    ss.registerCallback(Events.get().grpcServerRequestMessage(), new GrpcRequestMessageHandler());
  }
}
