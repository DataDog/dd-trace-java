package datadog.trace.instrumentation.openfeature;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Keeps legacy providers usable as default-value providers after their bootstrap API is removed.
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class LegacyProviderInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {
  public LegacyProviderInstrumentation() {
    super("openfeature");
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    // Application safety must not depend on whether Feature Flags is enabled.
    return true;
  }

  @Override
  public String instrumentedType() {
    return "datadog.trace.api.openfeature.Provider";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    // The current SDK's deprecated alias has no legacy evaluator to replace.
    return declaresMethod(named("loadEvaluatorClass").and(takesArguments(0)));
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        named("initialize").and(takesArguments(1)),
        packageName + ".LegacyProviderAdvice$Initialize");
    transformer.applyAdvice(
        named("getProviderHooks").and(takesArguments(0)),
        packageName + ".LegacyProviderAdvice$Hooks");
    transformer.applyAdvice(
        namedOneOf(
                "getBooleanEvaluation",
                "getStringEvaluation",
                "getIntegerEvaluation",
                "getDoubleEvaluation",
                "getObjectEvaluation")
            .and(takesArguments(3)),
        packageName + ".LegacyProviderAdvice$Evaluation");
  }
}
