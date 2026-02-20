package datadog.trace.instrumentation.tomcat9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.ClassloaderConfigurationOverrides.DATADOG_TAGS_JNDI_PREFIX;
import static datadog.trace.api.ClassloaderConfigurationOverrides.DATADOG_TAGS_PREFIX;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.tomcat.util.descriptor.web.ContextEnvironment;

@AutoService(InstrumenterModule.class)
public class WebappClassLoaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String TOMCAT = "tomcat";

  public WebappClassLoaderInstrumentation() {
    super(TOMCAT, "tomcat-classloading");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.loader.WebappClassLoaderBase";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("setResources")), getClass().getName() + "$CaptureWebappNameAdvice");
  }

  public static class CaptureWebappNameAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onContextAvailable(
        @Advice.This final WebappClassLoaderBase classLoader,
        @Advice.Argument(0) final WebResourceRoot webResourceRoot) {
      // at this moment we have the context set in this classloader, hence its name
      final Context context = webResourceRoot != null ? webResourceRoot.getContext() : null;
      if (context == null) {
        return;
      }
      ClassloaderConfigurationOverrides.ContextualInfo info = null;

      final String contextName = context.getBaseName();
      if (contextName != null && !contextName.isEmpty()) {
        info =
            ClassloaderConfigurationOverrides.withPinnedServiceName(
                classLoader, contextName, TOMCAT);
      }
      if (context.getNamingResources() != null) {
        final ContextEnvironment[] envs = context.getNamingResources().findEnvironments();
        if (envs != null) {
          final Map<String, String> tags = new HashMap<>();
          for (final ContextEnvironment env : envs) {
            // as a limitation here we simplify a lot the logic and we do not try to resolve the
            // typed value but we just take the string representation. It avoids implementing the
            // logic to convert to other types
            // (i.e. long, double) or instrument more deeply tomcat naming
            if (env.getValue() == null || env.getValue().isEmpty()) {
              continue;
            }
            String name = null;
            if (env.getName().startsWith(DATADOG_TAGS_PREFIX)) {
              name = env.getName().substring(DATADOG_TAGS_PREFIX.length());
            } else if (env.getName().startsWith(DATADOG_TAGS_JNDI_PREFIX)) {
              name = env.getName().substring(DATADOG_TAGS_JNDI_PREFIX.length());
            }
            if (name != null && !name.isEmpty()) {
              tags.put(name, env.getValue());
            }
          }
          if (!tags.isEmpty()) {
            if (info == null) {
              info = ClassloaderConfigurationOverrides.maybeCreateContextualInfo(classLoader);
            }
            tags.forEach(info::addTag);
          }
        }
      }
    }
  }
}
