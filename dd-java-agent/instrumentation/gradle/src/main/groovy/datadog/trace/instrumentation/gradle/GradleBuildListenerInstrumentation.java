package datadog.trace.instrumentation.gradle;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.gradle.invocation.DefaultGradle;

@AutoService(Instrumenter.class)
public class GradleBuildListenerInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradleBuildListenerInstrumentation() {
    super("gradle", "gradle-build-listener");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.invocation.DefaultGradle";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GradleUtils",
      packageName + ".GradleProjectConfigurator",
      packageName + ".GradleProjectConfigurator$_configureCompilerPlugin_closure1",
      packageName + ".GradleProjectConfigurator$_configureJacoco_closure2",
      packageName + ".GradleProjectConfigurator$_configureJacoco_closure3",
      packageName + ".GradleProjectConfigurator$_forEveryTestTask_closure4",
      packageName + ".GradleProjectConfigurator$_configureProject_closure5",
      packageName + ".GradleBuildListener",
      packageName + ".GradleBuildListener$TestTaskExecutionListener"
    };
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  public static class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstructor(@Advice.This final DefaultGradle gradle) {
      gradle.addBuildListener(new GradleBuildListener());
    }
  }
}
