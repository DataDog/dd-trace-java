package datadog.trace.instrumentation.log4j2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Provides the service field on versions predating Log4j2's ContextDataInjector. */
@AutoService(InstrumenterModule.class)
public class LogEventServiceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public LogEventServiceInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogPatternInject();
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return not(hasClassNamed("org.apache.logging.log4j.core.impl.ContextDataInjectorFactory"));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.logging.log4j.core.impl.Log4jLogEvent",
      "org.apache.logging.log4j.core.async.RingBufferLogEvent"
    };
  }

  @Override
  public String muzzleDirective() {
    return "default";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(isDeclaredBy(named("org.apache.logging.log4j.core.impl.Log4jLogEvent")))
            // AsyncLogger merges configured properties on its consumer before calling appenders.
            .or(isMethod().and(named("mergePropertiesIntoContextMap"))),
        getClass().getName() + "$ServiceAdvice");
  }

  public static class ServiceAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue(value = "contextMap", readOnly = false) Map<String, String> context) {
      Config config = Config.get();
      if (!config.isLogPatternInject()
          || !traceConfig().isLogsInjectionEnabled()
          || (context != null && context.containsKey("dd.service"))) {
        return;
      }
      String service = config.getServiceName();
      if (service == null || service.isEmpty()) {
        return;
      }
      if (context == null || context.isEmpty()) {
        context = singletonMap("dd.service", service);
      } else {
        Map<String, String> copy = new HashMap<>(context);
        copy.put("dd.service", service);
        context = copy;
      }
    }
  }
}
