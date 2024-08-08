package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;

@AutoService(InstrumenterModule.class)
public class GradleBuildScopeServicesInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradleBuildScopeServicesInstrumentation() {
    super("gradle", "gradle-build-scope-services");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
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
      packageName + ".CiVisibilityGradleListenerInjector",
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
        @Advice.Argument(0) final ServiceRegistry parentServices) {
      CiVisibilityGradleListenerInjector.inject(parentServices, buildScopeServices);
    }
  }
}
