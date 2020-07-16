package datadog.trace.instrumentation.slf4j.mdc;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.LogContextScopeListener;
import datadog.trace.agent.tooling.log.ThreadLocalWithDDTagsInitValue;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
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

  private boolean initialized = false;

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

  @Override
  public void postMatch(
      final TypeDescription typeDescription,
      final ClassLoader classLoader,
      final JavaModule module,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain) {
    if (classBeingRedefined != null && !initialized) {
      MDCAdvice.mdcClassInitialized(classBeingRedefined);
    }
    initialized = true;
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        new BooleanMatcher<MethodDescription>(false) {
          @Override
          public boolean matches(final MethodDescription target) {
            return initialized;
          }
        }.and(isTypeInitializer()),
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
        GlobalTracer.get().addScopeListener(new LogContextScopeListener(putMethod, removeMethod));

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
            copyOnThreadLocalFieldValue != null ? copyOnThreadLocalFieldValue : new HashMap<>();
        copyOnThreadLocalField.set(
            mdcAdapterInstance, ThreadLocalWithDDTagsInitValue.create(copyOnThreadLocalFieldValue));

      } catch (final NoSuchMethodException
          | IllegalAccessException
          | NoSuchFieldException
          | InvocationTargetException e) {
        org.slf4j.LoggerFactory.getLogger(mdcClass).debug("Failed to add MDC span listener", e);
      }
    }
  }
}
