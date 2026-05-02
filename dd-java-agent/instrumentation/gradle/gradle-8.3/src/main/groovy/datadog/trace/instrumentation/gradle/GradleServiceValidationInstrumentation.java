package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;

/**
 * We inject {@link CiVisibilityGradleListener} into Gradle and register it with {@code
 * Scope.Build.class} scope.
 *
 * <p>A class named {@code org.gradle.internal.service.ServiceScopeValidator} checks that every
 * service registered with a scope is annotated with {@code @ServiceScope(<ScopeClass>.class)}.
 *
 * <p>We cannot annotate our service, as {@code Scope.Build.class} is a recent addition: we need to
 * support older Gradle versions, besides this class is absent in the Gradle API version that the
 * tracer currently uses.
 *
 * <p>To suppress validation for our service we patch a "workaround" class that is used internally
 * by Gradle.
 */
@AutoService(InstrumenterModule.class)
public class GradleServiceValidationInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GradleServiceValidationInstrumentation() {
    super("gradle", "gradle-build-scope-services");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.internal.service.ServiceScopeValidatorWorkarounds";
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("shouldSuppressValidation").and(takesArgument(0, Class.class)),
        getClass().getName() + "$SuppressValidation");
  }

  public static class SuppressValidation {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void suppressValidationForCiVisService(
        @Advice.Argument(0) final Class<?> validatedClass,
        @Advice.Return(readOnly = false) boolean suppressValidation) {
      if (validatedClass.getName().endsWith("CiVisibilityGradleListener")) {
        suppressValidation = true;
      }
    }
  }
}
