package datadog.trace.instrumentation.slf4j.mdc;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.LogContextScopeListener;
import datadog.trace.agent.tooling.log.ThreadLocalWithDDTagsInitValue;
import datadog.trace.api.Config;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.BooleanMatcher;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

@AutoService(Instrumenter.class)
public class MDCInjectionInstrumentation extends Instrumenter.Default {
  public static final String MDC_INSTRUMENTATION_NAME = "mdc";

  // Intentionally doing the string replace to bypass gradle shadow rename
  // mdcClassName = org.slf4j.MDC
  private static final String mdcClassName = "org.TMP.MDC".replaceFirst("TMP", "slf4j");

  private volatile boolean initialized = false;

  public MDCInjectionInstrumentation() {
    super(MDC_INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named(mdcClassName);
  }

  // The reason why this `postMatch` hook and the `initialized` variable exists is that `slf4j` is
  // used by our test harness, and the MDC class will be loaded and initialized before the agent is
  // installed, and we will redefine the MDC class, but we will not run the constructor again, so we
  // need to do that work here. It's also ok that there is only one initialized variable, since
  // in real situations, the agent is installed so early that the transformer will be applied
  // regardless of if the MDC is being loaded in multiple class loaders.
  @Override
  public void postMatch(
      final TypeDescription typeDescription,
      final ClassLoader classLoader,
      final JavaModule module,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain) {
    if (classBeingRedefined != null && !initialized) {
      MDCAdvice.mdcClassInitialized(classBeingRedefined);
      initialized = true;
    }
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer()
            .and(
                new BooleanMatcher<MethodDescription>(true) {
                  @Override
                  public boolean matches(final MethodDescription target) {
                    initialized = true;
                    return true;
                  }
                }),
        MDCInjectionInstrumentation.class.getName() + "$MDCAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.LogContextScopeListener",
      "datadog.trace.agent.tooling.log.ThreadLocalWithDDTagsInitValue",
    };
  }

  public static class MDCAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized(@Advice.Origin final Class<?> mdcClass) {
      try {
        final Method putMethod = mdcClass.getMethod("put", String.class, String.class);
        final Method removeMethod = mdcClass.getMethod("remove", String.class);
        LogContextScopeListener.add("slf4j", putMethod, removeMethod);

        if (Config.get().isLogsMDCTagsInjectionEnabled()) {
          final Field mdcAdapterField = mdcClass.getDeclaredField("mdcAdapter");
          mdcAdapterField.setAccessible(true);
          final Object mdcAdapterInstance = mdcAdapterField.get(null);
          final Field copyOnThreadLocalField =
              mdcAdapterInstance.getClass().getDeclaredField("copyOnThreadLocal");
          if (!ThreadLocal.class.isAssignableFrom(copyOnThreadLocalField.getType())) {
            org.slf4j.LoggerFactory.getLogger(mdcClass)
                .debug("Can't find thread local field: {}", copyOnThreadLocalField);
            return;
          }
          copyOnThreadLocalField.setAccessible(true);
          Object copyOnThreadLocalFieldValue =
              ((ThreadLocal) copyOnThreadLocalField.get(mdcAdapterInstance)).get();
          copyOnThreadLocalFieldValue =
              copyOnThreadLocalFieldValue != null
                  ? copyOnThreadLocalFieldValue
                  : Collections.synchronizedMap(new HashMap<>());
          copyOnThreadLocalField.set(
              mdcAdapterInstance,
              ThreadLocalWithDDTagsInitValue.create(copyOnThreadLocalFieldValue));
        } else {
          org.slf4j.LoggerFactory.getLogger(mdcClass)
              .debug("Skip injection tags in thread context logger, because of Config setting");
        }
      } catch (final NoSuchMethodException
          | IllegalAccessException
          | NoSuchFieldException
          | InvocationTargetException e) {
        org.slf4j.LoggerFactory.getLogger(mdcClass).debug("Failed to add MDC span listener", e);
      }
    }
  }
}
