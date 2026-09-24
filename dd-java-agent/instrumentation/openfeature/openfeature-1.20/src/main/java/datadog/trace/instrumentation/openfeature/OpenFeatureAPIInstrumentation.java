package datadog.trace.instrumentation.openfeature;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class OpenFeatureAPIInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public OpenFeatureAPIInstrumentation() {
    super("openfeature");
  }

  @Override
  public boolean isEnabled() {
    // Provider installation belongs to Feature Flags, not tracing integrations.
    return InstrumenterConfig.get().isFeatureFlaggingInstrumentationEnabled();
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.FEATURE_FLAGS);
  }

  @Override
  public String instrumentedType() {
    return "dev.openfeature.sdk.OpenFeatureAPI";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.api.openfeature.Evaluator",
      "com.datadog.featureflag.core.EvaluationContext",
      "com.datadog.featureflag.core.EvaluationMetadata",
      "com.datadog.featureflag.core.EvaluationMetadata$EvaluationMetadataBuilder",
      "com.datadog.featureflag.core.EvaluationResult",
      "com.datadog.featureflag.core.EvaluationResult$Builder",
      "com.datadog.featureflag.core.EvaluationResult$ErrorCode",
      "com.datadog.featureflag.core.EvaluationResult$Reason",
      "com.datadog.featureflag.core.ConfigurationStore",
      "com.datadog.featureflag.core.FlagEvaluator",
      "com.datadog.featureflag.core.FlagEvaluator$1",
      "com.datadog.featureflag.core.FlagEvaluator$NumberComparator",
      "com.datadog.featureflag.core.FlagEvaluator$SemverComparator",
      "datadog.trace.api.openfeature.DDEvaluator$1",
      "datadog.trace.api.openfeature.DDEvaluator$CopyResult",
      "datadog.trace.api.openfeature.DDEvaluator$FlattenEntry",
      "datadog.trace.api.openfeature.DDEvaluator",
      "datadog.trace.api.openfeature.FlagEvalLoggingHook",
      "datadog.trace.api.openfeature.FlagEvalMetrics",
      "datadog.trace.api.openfeature.FlagEvalMetricsHook",
      "datadog.trace.api.openfeature.SpanEnrichmentGate",
      "datadog.trace.api.openfeature.SpanEnrichmentHook",
      "datadog.trace.api.openfeature.Provider$InitializationState",
      "datadog.trace.api.openfeature.Provider$Options",
      "datadog.trace.api.openfeature.Provider",
      packageName + ".OpenFeatureProviderInstaller",
    };
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("getInstance"))
            .and(takesNoArguments())
            .and(returns(named("dev.openfeature.sdk.OpenFeatureAPI"))),
        OpenFeatureAPIInstrumentation.class.getName() + "$GetInstanceAdvice");
  }

  public static class GetInstanceAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void installProvider(@Advice.Return final OpenFeatureAPI api) {
      OpenFeatureProviderInstaller.install(api);
    }
  }
}
