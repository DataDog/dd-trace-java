package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import datadog.trace.logging.LogLevel;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class VMRuntimeInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  @Override
  public String instrumentedType() {
    return "org.graalvm.nativeimage.VMRuntime";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE, singletonList("com.oracle.svm.core.thread.VMOperationControl$VMOperationThread"));
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
      } else {
        String logLevel = Config.get().getLogLevel();
        if (null != logLevel) {
          GlobalLogLevelSwitcher.get().switchLevel(LogLevel.fromString(logLevel));
        }
      }

      datadog.trace.agent.tooling.nativeimage.TracerActivation.activate();
    }
  }
}
