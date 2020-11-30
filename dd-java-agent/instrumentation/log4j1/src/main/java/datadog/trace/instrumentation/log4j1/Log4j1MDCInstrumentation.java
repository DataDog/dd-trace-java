package datadog.trace.instrumentation.log4j1;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.LogContextScopeListener;
import datadog.trace.api.Config;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Log4j1MDCInstrumentation extends Instrumenter.Default {
  public static final String MDC_INSTRUMENTATION_NAME = "log4j1";

  public Log4j1MDCInstrumentation() {
    super(MDC_INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.apache.log4j.MDC");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer(), Log4j1MDCInstrumentation.class.getName() + "$MDCContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.agent.tooling.log.LogContextScopeListener"};
  }

  public static class MDCContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized(@Advice.Origin final Class<?> mdcClass) {
      try {
        final Method putMethod = mdcClass.getMethod("put", String.class, Object.class);
        final Method removeMethod = mdcClass.getMethod("remove", String.class);
        LogContextScopeListener.add("log4j1", putMethod, removeMethod);

        if (Config.get().isLogsMDCTagsInjectionEnabled()) {
          // log4j1 uses subclass of InheritableThreadLocal and we don't need to modify private
          // thread
          // local field:
          LogContextScopeListener.addDDTagsToMDC(putMethod);
        } else {
          org.slf4j.LoggerFactory.getLogger(mdcClass)
              .debug("Skip injection tags in thread context logger, because of Config setting");
        }
      } catch (final NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        org.slf4j.LoggerFactory.getLogger(mdcClass)
            .debug("Failed to add log4j ThreadContext span listener", e);
      }
    }
  }
}
