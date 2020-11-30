package datadog.trace.instrumentation.log4j2;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ThreadContextInstrumentation extends Instrumenter.Default {
  private static final String TYPE_NAME = "org.apache.logging.log4j.ThreadContext";
  private static final String MDC_INSTRUMENTATION_NAME = "log4j";

  public ThreadContextInstrumentation() {
    super(MDC_INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named(TYPE_NAME);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer(), ThreadContextInstrumentation.class.getName() + "$ThreadContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.LogContextScopeListener",
      "datadog.trace.agent.tooling.log.ThreadLocalWithDDTagsInitValue",
    };
  }

  public static class ThreadContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized(@Advice.Origin final Class<?> threadContextClass) {
      try {
        final Method putMethod = threadContextClass.getMethod("put", String.class, String.class);
        final Method removeMethod = threadContextClass.getMethod("remove", String.class);
        LogContextScopeListener.add("log4j2", putMethod, removeMethod);

        if (Config.get().isLogsMDCTagsInjectionEnabled()) {
          final Field contextMapField = threadContextClass.getDeclaredField("contextMap");
          contextMapField.setAccessible(true);
          final Object contextMap = contextMapField.get(null);
          if (contextMap
              .getClass()
              .getCanonicalName()
              .equals("org.apache.logging.slf4j.MDCContextMap")) {
            org.slf4j.LoggerFactory.getLogger(threadContextClass)
                .debug(
                    "Log4j to SLF4J Adapter detected. "
                        + TYPE_NAME
                        + "'s ThreadLocal"
                        + " field will not be instrumented because it delegates to slf4-MDC");
            return;
          }
          final Field localMapField = contextMap.getClass().getDeclaredField("localMap");
          if (!ThreadLocal.class.isAssignableFrom(localMapField.getType())) {
            org.slf4j.LoggerFactory.getLogger(threadContextClass)
                .debug("Can't find thread local field: {}", localMapField);
            return;
          }

          localMapField.setAccessible(true);
          final Object threadLocalInitValue = ((ThreadLocal) localMapField.get(contextMap)).get();
          final String fullTypeWithGeneric = localMapField.getGenericType().toString();
          if ("java.lang.ThreadLocal<java.util.Map<java.lang.String, java.lang.String>>"
              .equals(fullTypeWithGeneric)) {
            final Map<String, String> threadLocalInitValueAsMap =
                threadLocalInitValue != null
                    ? (Map<String, String>) threadLocalInitValue
                    : Collections.synchronizedMap(new HashMap<String, String>());
            org.slf4j.LoggerFactory.getLogger(threadContextClass)
                .debug("Setting {} for ThreadLocalWithDDTagsInitValue ", threadLocalInitValueAsMap);
            localMapField.set(
                contextMap, ThreadLocalWithDDTagsInitValue.create(threadLocalInitValueAsMap));
          } else if ("java.lang.ThreadLocal<org.apache.logging.log4j.util.StringMap>"
              .equals(fullTypeWithGeneric)) {
            final Object threadLocalInitValueAsStringMap =
                threadLocalInitValue != null
                    ? threadLocalInitValue
                    : Class.forName("org.apache.logging.log4j.util.SortedArrayStringMap")
                        .newInstance();
            org.slf4j.LoggerFactory.getLogger(threadContextClass)
                .debug(
                    "Setting {} for ThreadLocalWithDDTagsInitValue ",
                    threadLocalInitValueAsStringMap);
            localMapField.set(
                contextMap, ThreadLocalWithDDTagsInitValue.create(threadLocalInitValueAsStringMap));
          } else {
            org.slf4j.LoggerFactory.getLogger(threadContextClass)
                .warn(
                    "can't find thread local for {}; skipping adding extra tags",
                    fullTypeWithGeneric);
          }
        } else {
          org.slf4j.LoggerFactory.getLogger(threadContextClass)
              .debug("Skip injection tags in thread context logger, because of Config setting");
        }
      } catch (final NoSuchMethodException
          | NoSuchFieldException
          | IllegalAccessException
          | InvocationTargetException e) {
        org.slf4j.LoggerFactory.getLogger(threadContextClass)
            .debug("Failed to add log4j ThreadContext span listener", e);
      } catch (final Throwable t) {
        org.slf4j.LoggerFactory.getLogger(threadContextClass)
            .debug("Unexpected exception while adding log4j ThreadContext span listener", t);
      }
    }
  }
}
