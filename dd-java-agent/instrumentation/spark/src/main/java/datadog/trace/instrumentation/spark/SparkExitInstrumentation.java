package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;

@AutoService(InstrumenterModule.class)
public class SparkExitInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, Instrumenter.ForBootstrap {

  public SparkExitInstrumentation() {
    super("spark-exit");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isDataJobsEnabled();
  }

  @Override
  public String instrumentedType() {
    return "java.lang.Runtime";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("exit").and(isDeclaredBy(named("java.lang.Runtime"))),
        packageName + ".SparkExitAdvice");
  }
}
