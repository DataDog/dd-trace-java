package com.datadog.iast;

import static datadog.trace.api.ProductActivation.FULLY_ENABLED;
import static datadog.trace.api.iast.IastContext.Mode.GLOBAL;
import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.propagation.CodecModuleImpl;
import com.datadog.iast.propagation.PropagationModuleImpl;
import com.datadog.iast.propagation.StringModuleImpl;
import com.datadog.iast.securitycontrol.IastSecurityControlTransformer;
import com.datadog.iast.sink.ApplicationModuleImpl;
import com.datadog.iast.sink.CommandInjectionModuleImpl;
import com.datadog.iast.sink.EmailInjectionModuleImpl;
import com.datadog.iast.sink.HardcodedSecretModuleImpl;
import com.datadog.iast.sink.HeaderInjectionModuleImpl;
import com.datadog.iast.sink.HstsMissingHeaderModuleImpl;
import com.datadog.iast.sink.HttpResponseHeaderModuleImpl;
import com.datadog.iast.sink.InsecureAuthProtocolModuleImpl;
import com.datadog.iast.sink.InsecureCookieModuleImpl;
import com.datadog.iast.sink.LdapInjectionModuleImpl;
import com.datadog.iast.sink.NoHttpOnlyCookieModuleImpl;
import com.datadog.iast.sink.NoSameSiteCookieModuleImpl;
import com.datadog.iast.sink.PathTraversalModuleImpl;
import com.datadog.iast.sink.ReflectionInjectionModuleImpl;
import com.datadog.iast.sink.SqlInjectionModuleImpl;
import com.datadog.iast.sink.SsrfModuleImpl;
import com.datadog.iast.sink.StacktraceLeakModuleImpl;
import com.datadog.iast.sink.TrustBoundaryViolationModuleImpl;
import com.datadog.iast.sink.UntrustedDeserializationModuleImpl;
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
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.securitycontrol.SecurityControl;
import datadog.trace.api.iast.securitycontrol.SecurityControlFormatter;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastSystem.class);
  public static boolean DEBUG = false;
  public static Verbosity VERBOSITY = Verbosity.OFF;

  public static void start(final SubscriptionService ss) {
    start(null, ss, null);
  }

  public static void start(final SubscriptionService ss, OverheadController overheadController) {
    start(null, ss, overheadController);
  }

  public static void start(final Instrumentation instrumentation, final SubscriptionService ss) {
    start(instrumentation, ss, null);
  }

  public static void start(
      @Nullable final Instrumentation instrumentation,
      final SubscriptionService ss,
      @Nullable OverheadController overheadController) {
    final Config config = Config.get();
    final ProductActivation iast = config.getIastActivation();
    final ProductActivation appSec = config.getAppSecActivation();
    if (iast != FULLY_ENABLED && appSec != FULLY_ENABLED) {
      LOGGER.debug("IAST is disabled: iast={}, appSec={}", iast, appSec);
      return;
    }
    DEBUG = config.isIastDebugEnabled();
    VERBOSITY = config.getIastTelemetryVerbosity();
    LOGGER.debug("IAST is starting: debug={}, verbosity={}", DEBUG, VERBOSITY);
    if (VERBOSITY != Verbosity.OFF) {
      IastMetricCollector.register(new IastMetricCollector());
    }
    final Reporter reporter = new Reporter(config, AgentTaskScheduler.get());
    final boolean globalContext = config.getIastContextMode() == GLOBAL;
    final IastContext.Provider contextProvider = contextProvider(iast, globalContext);
    if (overheadController == null) {
      overheadController =
          OverheadController.build(
              globalContext ? UNLIMITED : config.getIastRequestSampling(),
              config.getIastMaxConcurrentRequests(),
              globalContext,
              AgentTaskScheduler.get());
    }
    IastContext.Provider.register(contextProvider);
    final Dependencies dependencies =
        new Dependencies(
            config, reporter, overheadController, StackWalkerFactory.INSTANCE, contextProvider);
    final boolean addTelemetry = config.getIastTelemetryVerbosity() != Verbosity.OFF;
    iastModules(iast, dependencies).forEach(InstrumentationBridge::registerIastModule);
    registerRequestStartedCallback(ss, addTelemetry, dependencies);
    registerRequestEndedCallback(ss, addTelemetry, dependencies);
    registerHeadersCallback(ss);
    registerHttpRouteCallback(ss);
    registerGrpcServerRequestMessageCallback(ss);
    maybeApplySecurityControls(instrumentation);
    LOGGER.debug("IAST started");
  }

  private static void maybeApplySecurityControls(@Nullable Instrumentation instrumentation) {
    if (Config.get().getIastSecurityControlsConfiguration() == null || instrumentation == null) {
      return;
    }
    Map<String, List<SecurityControl>> securityControls =
        SecurityControlFormatter.format(Config.get().getIastSecurityControlsConfiguration());
    if (securityControls == null) {
      LOGGER.warn("No security controls to apply");
      return;
    }
    instrumentation.addTransformer(new IastSecurityControlTransformer(securityControls), true);
  }

  private static IastContext.Provider contextProvider(
      final ProductActivation iast, final boolean global) {
    if (iast != FULLY_ENABLED) {
      return new IastOptOutContext.Provider();
    } else {
      return global ? new IastGlobalContext.Provider() : new IastRequestContext.Provider();
    }
  }

  private static Stream<IastModule> iastModules(
      final ProductActivation iast, final Dependencies dependencies) {
    Stream<Class<? extends IastModule>> modules =
        Stream.of(
            StringModuleImpl.class,
            CodecModuleImpl.class,
            SqlInjectionModuleImpl.class,
            PathTraversalModuleImpl.class,
            CommandInjectionModuleImpl.class,
            WeakCipherModuleImpl.class,
            WeakHashModuleImpl.class,
            LdapInjectionModuleImpl.class,
            PropagationModuleImpl.class,
            HttpResponseHeaderModuleImpl.class,
            HstsMissingHeaderModuleImpl.class,
            InsecureCookieModuleImpl.class,
            NoHttpOnlyCookieModuleImpl.class,
            XContentTypeModuleImpl.class,
            NoSameSiteCookieModuleImpl.class,
            SsrfModuleImpl.class,
            UnvalidatedRedirectModuleImpl.class,
            WeakRandomnessModuleImpl.class,
            XPathInjectionModuleImpl.class,
            TrustBoundaryViolationModuleImpl.class,
            XssModuleImpl.class,
            StacktraceLeakModuleImpl.class,
            HeaderInjectionModuleImpl.class,
            ApplicationModuleImpl.class,
            HardcodedSecretModuleImpl.class,
            InsecureAuthProtocolModuleImpl.class,
            ReflectionInjectionModuleImpl.class,
            UntrustedDeserializationModuleImpl.class,
            EmailInjectionModuleImpl.class);
    if (iast != FULLY_ENABLED) {
      modules = modules.filter(IastSystem::isOptOut);
    }
    return modules.map(type -> newIastModule(dependencies, type));
  }

  private static boolean isOptOut(final Class<? extends IastModule> module) {
    for (final Class<?> itf : module.getInterfaces()) {
      if (itf.getDeclaredAnnotation(IastModule.OptOut.class) != null) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static <M extends IastModule> M newIastModule(
      final Dependencies dependencies, final Class<M> type) {
    try {
      for (final Constructor<?> ctor : type.getDeclaredConstructors()) {
        switch (ctor.getParameterCount()) {
          case 0:
            return (M) ctor.newInstance();
          case 1:
            if (ctor.getParameterTypes()[0] == Dependencies.class) {
              return (M) ctor.newInstance(dependencies);
            }
            break;
        }
      }
      throw new RuntimeException("Cannot find constructor for the module " + type);
    } catch (final Throwable e) {
      // should never happen and be caught on IAST tests
      throw new UndeclaredThrowableException(
          e,
          "Modules should have either default constructor or take only one param of type Dependencies");
    }
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

  private static void registerHttpRouteCallback(final SubscriptionService ss) {
    ss.registerCallback(Events.get().httpRoute(), new HttpRouteHandler());
  }

  private static void registerGrpcServerRequestMessageCallback(final SubscriptionService ss) {
    ss.registerCallback(Events.get().grpcServerRequestMessage(), new GrpcRequestMessageHandler());
  }
}
