package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.Config;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.InvokeDynamicHelperContainer;
import datadog.trace.api.iast.InvokeDynamicHelperRegistry;
import datadog.trace.util.AgentTaskScheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
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

    final Reporter reporter = new Reporter(config);
    if (overheadController == null) {
      overheadController = new OverheadController(config, AgentTaskScheduler.INSTANCE);
    }
    initializeInvokeDynamicHelperRegistry();
    final IastModule iastModule = new IastModuleImpl();
    InstrumentationBridge.registerIastModule(iastModule);
    registerRequestStartedCallback(ss, overheadController, reporter);
    registerRequestEndedCallback(ss, overheadController);
  }

  private static void initializeInvokeDynamicHelperRegistry() {
    InvokeDynamicHelperRegistry.reset();
    doWithServiceClasses(
        InvokeDynamicHelperContainer.class,
        IastSystem.class.getClassLoader(),
        cshc -> InvokeDynamicHelperRegistry.registerHelperContainer(MethodHandles.lookup(), cshc));
  }

  private static void registerRequestStartedCallback(
      final SubscriptionService ss,
      final OverheadController overheadController,
      final Reporter reporter) {
    final EventType<Supplier<Flow<Object>>> event = Events.get().requestStarted();
    ss.registerCallback(event, new RequestStartedHandler(overheadController, reporter));
  }

  private static void registerRequestEndedCallback(
      final SubscriptionService ss, final OverheadController overheadController) {
    final EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> event =
        Events.get().requestEnded();
    ss.registerCallback(event, new RequestEndedHandler(overheadController));
  }

  @SuppressFBWarnings("OS_OPEN_STREAM")
  private static <T> void doWithServiceClasses(
      Class<T> cls, ClassLoader cl, Consumer<Class<? extends T>> consumer) {
    try (InputStream classListIs = cl.getResourceAsStream("META-INF/services/" + cls.getName())) {
      if (classListIs == null) {
        return;
      }

      BufferedReader r =
          new BufferedReader(new InputStreamReader(classListIs, StandardCharsets.US_ASCII));
      r.lines()
          .map(
              line -> {
                try {
                  return (Class<? extends T>) cl.loadClass(line);
                } catch (ClassNotFoundException e) {
                  throw new UndeclaredThrowableException(e);
                }
              })
          .forEachOrdered(consumer);
    } catch (IOException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}
