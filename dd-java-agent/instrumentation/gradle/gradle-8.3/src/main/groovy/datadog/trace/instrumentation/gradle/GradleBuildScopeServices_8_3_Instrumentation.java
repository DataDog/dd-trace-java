package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;

@AutoService(InstrumenterModule.class)
public class GradleBuildScopeServices_8_3_Instrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GradleBuildScopeServices_8_3_Instrumentation() {
    super("gradle", "gradle-build-scope-services");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Instrument Gradle [8.3 ... 8.10)
    return hasClassNamed("org.gradle.api.file.ConfigurableFilePermissions")
        .and(not(hasClassNamed("org.gradle.internal.classpath.transforms.AdhocInterceptors")));
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.internal.service.scopes.BuildScopeServices";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CiVisibilityGradleListenerInjector_8_3",
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
        @Advice.This final DefaultServiceRegistry buildScopeServices,
        @Advice.Argument(0) final ServiceRegistry parentServices) {
      CiVisibilityGradleListenerInjector_8_3.injectCiVisibilityGradleListener(
          buildScopeServices, parentServices);
    }
  }

  @Override
  public String muzzleDirective() {
    return "skipMuzzle";
  }
}
