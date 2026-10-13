package datadog.trace.instrumentation.openfeature;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.FEATURE_FLAGS;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Set;

/**
 * Connects the {@code dd-openfeature} SDK to the agent: Remote Configuration, the event platform
 * proxy, health metrics and span enrichment.
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class DDOpenFeatureInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DDOpenFeatureInstrumentation() {
    super("openfeature");
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(FEATURE_FLAGS);
  }

  @Override
  public String instrumentedType() {
    return "com.datadog.openfeature.internal.connector.Connectors";
  }

  @Override
  public String[] helperClassNames() {
    // Helpers are compiled in the java11 source set, out of reach of helper discovery.
    return new String[] {
      packageName + ".AgentConfigurationSource",
      packageName + ".AgentEventProxy",
      packageName + ".AgentHealthMetrics",
      packageName + ".AgentSpanEnricher",
      packageName + ".JavaAgentConnector",
    };
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isStatic()).and(named("detect")).and(takesNoArguments()),
        packageName + ".DetectConnectorAdvice");
  }
}
