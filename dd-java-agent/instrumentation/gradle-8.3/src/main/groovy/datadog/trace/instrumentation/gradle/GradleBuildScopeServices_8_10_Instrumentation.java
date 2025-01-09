package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.Scope;

@AutoService(InstrumenterModule.class)
public class GradleBuildScopeServices_8_10_Instrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GradleBuildScopeServices_8_10_Instrumentation() {
    super("gradle", "gradle-build-scope-services");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Instrument Gradle [8.10 ... )
    return hasClassNamed("org.gradle.internal.classpath.transforms.AdhocInterceptors");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.internal.service.ScopedServiceRegistry";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CiVisibilityGradleListenerInjector_8_10",
    };
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, Class.class))
            .and(takesArgument(1, boolean.class))
            .and(takesArgument(3, named("org.gradle.internal.service.ServiceRegistry[]"))),
        getClass().getName() + "$Construct");
  }

  public static class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstructor(
        @Advice.This final DefaultServiceRegistry buildScopeServices,
        @Advice.Argument(0) final Class<? extends Scope> scope,
        @Advice.Argument(3) final ServiceRegistry[] parentServices) {
      if (scope.getSimpleName().equals("Build")) {
        CiVisibilityGradleListenerInjector_8_10.injectCiVisibilityGradleListener(
            buildScopeServices, parentServices);
      }
    }
  }

  @Override
  public String muzzleDirective() {
    return "skipMuzzle";
  }
}
