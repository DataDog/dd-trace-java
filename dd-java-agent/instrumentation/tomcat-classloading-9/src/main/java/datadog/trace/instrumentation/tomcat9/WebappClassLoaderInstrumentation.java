package datadog.trace.instrumentation.tomcat9;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.naming.ClassloaderServiceNames;
import net.bytebuddy.asm.Advice;
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
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ContextNameHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureWebappNameAdvice");
  }

  public static class CaptureWebappNameAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(@Advice.This final WebappClassLoaderBase classLoader) {
      ClassloaderServiceNames.addIfMissing(classLoader, ContextNameHelper.ADDER);
    }
  }
}
