package datadog.trace.instrumentation.jbosslogmanager;

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
public class JBossLogManagerMDCInstrumentation extends Instrumenter.Default {
  public static final String MDC_INSTRUMENTATION_NAME = "jboss-logmanager";

  public JBossLogManagerMDCInstrumentation() {
    super(MDC_INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.jboss.logmanager.MDC");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer(),
        JBossLogManagerMDCInstrumentation.class.getName() + "$MDCContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.agent.tooling.log.LogContextScopeListener"};
  }

  public static class MDCContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized(@Advice.Origin final Class<?> mdcClass) {
      try {
        final Method putMethod = mdcClass.getMethod("put", String.class, String.class);
        final Method removeMethod = mdcClass.getMethod("remove", String.class);
        LogContextScopeListener.add("jboss-logmanager", putMethod, removeMethod);

        if (Config.get().isLogsMDCTagsInjectionEnabled()) {
          LogContextScopeListener.addDDTagsToMDC(putMethod);
        } else {
          org.slf4j.LoggerFactory.getLogger(mdcClass)
              .debug("Skip injection tags in thread context logger, because of Config setting");
        }
      } catch (final NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        org.slf4j.LoggerFactory.getLogger(mdcClass)
            .debug("Failed to add jboss-logmanager ThreadContext span listener", e);
      }
    }
  }
}
