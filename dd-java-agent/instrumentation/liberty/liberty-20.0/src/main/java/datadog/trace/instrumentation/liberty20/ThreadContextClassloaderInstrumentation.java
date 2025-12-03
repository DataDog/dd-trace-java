package datadog.trace.instrumentation.liberty20;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.ibm.ws.classloading.internal.ThreadContextClassLoader;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ThreadContextClassloaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ThreadContextClassloaderInstrumentation() {
    super("liberty", "liberty-classloading");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.classloading.internal.ThreadContextClassLoader";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BundleNameHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), getClass().getName() + "$ThreadContextClassloaderAdvice");
  }

  public static class ThreadContextClassloaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(@Advice.This ThreadContextClassLoader self) {
      final String name = BundleNameHelper.extractDeploymentName(self);
      if (name != null && !name.isEmpty()) {
        ClassloaderConfigurationOverrides.withPinnedServiceName(self, name);
      }
    }
  }
}
