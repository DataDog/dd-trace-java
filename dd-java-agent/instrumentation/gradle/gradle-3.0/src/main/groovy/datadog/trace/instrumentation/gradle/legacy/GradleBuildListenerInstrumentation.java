package datadog.trace.instrumentation.gradle.legacy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.gradle.invocation.DefaultGradle;

@AutoService(InstrumenterModule.class)
public class GradleBuildListenerInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GradleBuildListenerInstrumentation() {
    super("gradle", "gradle-build-listener");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Only instrument Gradle older than 8.3
    return not(hasClassNamed("org.gradle.api.file.ConfigurableFilePermissions"));
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
      packageName + ".GradleBuildListener",
      packageName + ".GradleBuildListener$TestTaskExecutionListener"
    };
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  public static class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstructor(@Advice.This final DefaultGradle gradle) {
      gradle.addBuildListener(new GradleBuildListener());
    }
  }

  @Override
  public String muzzleDirective() {
    return "skipMuzzle";
  }
}
