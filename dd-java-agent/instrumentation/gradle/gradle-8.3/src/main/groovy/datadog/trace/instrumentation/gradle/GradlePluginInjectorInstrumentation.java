package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class GradlePluginInjectorInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradlePluginInjectorInstrumentation() {
    super("gradle", "gradle-plugin-injector");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Only instrument Gradle 8.3+
    return hasClassNamed("org.gradle.api.file.ConfigurableFilePermissions");
  }

  /**
   * There are several class loaders in Gradle that load various parts of the system: core classes,
   * plugins, etc. CI Visibility listener and plugin (as well as the rest of the helper classes)
   * need to be injected into the plugins classloader because they refer to classes from Gradle
   * Testing JVM (e.g. {@link org.gradle.api.tasks.testing.Test}), which are loaded by the plugins
   * CL. The purpose of this instrumentation is only to inject the classes into the classloader, so
   * that they could be used from elsewhere.
   *
   * <p>{@link org.gradle.platform.base.Platform} was chosen as the instrumented type since it is
   * loaded early in the build lifecycle, therefore the injected classes can be safely used by the
   * services that are initialized later on.
   */
  @Override
  public String instrumentedType() {
    return "org.gradle.platform.base.Platform";
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CiVisibilityService",
      packageName + ".JavaCompilerPluginArgumentsProvider",
      packageName + ".TracerArgumentsProvider",
      packageName + ".AndroidGradleUtils",
      packageName + ".CiVisibilityGradleListener",
      packageName + ".CiVisibilityPluginExtension",
      packageName + ".CiVisibilityPlugin"
    };
  }

  @Override
  public boolean useAgentCodeSource() {
    return true;
  }
}
