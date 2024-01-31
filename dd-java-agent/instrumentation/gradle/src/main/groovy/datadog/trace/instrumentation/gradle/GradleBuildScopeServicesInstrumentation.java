package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;

@AutoService(Instrumenter.class)
public class GradleBuildScopeServicesInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradleBuildScopeServicesInstrumentation() {
    super("gradle", "gradle-build-scope-services");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Only instrument Gradle 8.3+
    return hasClassNamed("org.gradle.api.file.ConfigurableFilePermissions");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.internal.service.scopes.BuildScopeServices";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CiVisibilityGradleListenerProvider",
    };
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  public static class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstructor(
        @Advice.This final BuildScopeServices buildScopeServices,
        @Advice.Argument(0) final ServiceRegistry parent) {
      ClassLoaderRegistry classLoaderRegistry = parent.get(ClassLoaderRegistry.class);
      buildScopeServices.addProvider(new CiVisibilityGradleListenerProvider(classLoaderRegistry));
    }
  }
}
