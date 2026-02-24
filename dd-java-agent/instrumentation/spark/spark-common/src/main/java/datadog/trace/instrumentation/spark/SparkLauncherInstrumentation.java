package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.asm.Advice;
import org.apache.spark.launcher.SparkAppHandle;

@AutoService(InstrumenterModule.class)
public class SparkLauncherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SparkLauncherInstrumentation() {
    super("spark-launcher");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isDataJobsEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.apache.spark.launcher.SparkLauncher";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SparkConfAllowList", packageName + ".SparkLauncherListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("startApplication"))
            .and(isDeclaredBy(named("org.apache.spark.launcher.SparkLauncher"))),
        SparkLauncherInstrumentation.class.getName() + "$StartApplicationAdvice");
  }

  public static class StartApplicationAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(
        @Advice.This Object launcher,
        @Advice.Return SparkAppHandle handle,
        @Advice.Thrown Throwable throwable) {
      SparkLauncherListener.createLauncherSpan(launcher);

      if (throwable != null) {
        SparkLauncherListener.finishSpanWithThrowable(throwable);
        return;
      }

      if (handle != null) {
        handle.addListener(new SparkLauncherListener());
      }
    }
  }
}
