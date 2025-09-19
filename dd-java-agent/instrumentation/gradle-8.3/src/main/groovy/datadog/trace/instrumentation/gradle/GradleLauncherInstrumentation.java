package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation targets Gradle Launcher, which is the process that is started with
 * `gradle`/`gradlew` commands. The launcher starts Gradle Daemon (if not started yet), which is a
 * long-lived process that actually runs builds. The instrumentation injects the tracer and its
 * config properties into Gradle Daemon JVM settings when the daemon is started.
 */
@AutoService(InstrumenterModule.class)
public class GradleLauncherInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public GradleLauncherInstrumentation() {
    super("gradle", "gradle-daemon-jvm-options");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.gradle.launcher.configuration.AllProperties";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getProperties"),
        GradleLauncherInstrumentation.class.getName() + "$PropertiesAugmentationAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GradleDaemonInjectionUtils",
    };
  }

  public static class PropertiesAugmentationAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addJavaagentToGradleDaemonProperties(
        @Advice.Return(readOnly = false) Map<String, String> jvmOptions) {
      jvmOptions = GradleDaemonInjectionUtils.addJavaagentToGradleDaemonProperties(jvmOptions);
    }
  }
}
