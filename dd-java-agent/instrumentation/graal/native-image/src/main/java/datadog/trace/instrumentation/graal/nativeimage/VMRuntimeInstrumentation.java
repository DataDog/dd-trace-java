package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import datadog.trace.logging.LogLevel;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public final class VMRuntimeInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "org.graalvm.nativeimage.VMRuntime";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("initialize")),
        VMRuntimeInstrumentation.class.getName() + "$InitializeAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.agent.tooling.nativeimage.TracerActivation"};
  }

  @Override
  public boolean injectHelperDependencies() {
    return true;
  }

  public static class InitializeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      if (Config.get().isDebugEnabled()) {
        // was this native image originally built with debug off?
        Logger configLogger = LoggerFactory.getLogger(Config.class);
        if (!configLogger.isDebugEnabled()) {
          // patch logger level and re-log configuration details
          GlobalLogLevelSwitcher.get().switchLevel(LogLevel.DEBUG);
          configLogger.debug("New instance: {}", Config.get());
        }
      }

      datadog.trace.agent.tooling.nativeimage.TracerActivation.activate();
    }
  }
}
