package datadog.trace.instrumentation.tomcat9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.naming.ClassloaderServiceNames;
import net.bytebuddy.asm.Advice;
import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.loader.WebappClassLoaderBase;

@AutoService(InstrumenterModule.class)
public class WebappClassLoaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public WebappClassLoaderInstrumentation() {
    super("tomcat", "tomcat-classloading");
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
      final Context context = webResourceRoot.getContext();
      if (context != null) {
        final String contextName = context.getBaseName();
        if (contextName != null && !contextName.isEmpty()) {
          ClassloaderServiceNames.addServiceName(classLoader, contextName);
        }
      }
    }
  }
}
