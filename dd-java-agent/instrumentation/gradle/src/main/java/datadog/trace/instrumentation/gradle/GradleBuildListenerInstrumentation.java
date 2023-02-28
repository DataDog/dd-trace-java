package datadog.trace.instrumentation.gradle;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.gradle.invocation.DefaultGradle;

@AutoService(Instrumenter.class)
public class GradleBuildListenerInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradleBuildListenerInstrumentation() {
    super("gradle-build-listener");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.invocation.DefaultGradle";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GradleBuildListener",
      packageName + ".GradleBuildListener$TestFramework",
      packageName + ".GradleBuildListener$TestTaskExecutionListener",
      packageName + ".GradleDecorator"
    };
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
