package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.util.Set;

public class CalleeBenchmarkInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CalleeBenchmarkInstrumentation() {
    super("callee");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.connector.Request";
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getParameter").and(takesArguments(String.class)).and(returns(String.class)),
        CallSiteBenchmarkHelper.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {CallSiteBenchmarkHelper.class.getName()};
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON; // always active during this benchmark
  }

  @Override
  public boolean isEnabled(Set<TargetSystem> enabledSystems) {
    return "callee".equals(System.getProperty("dd.benchmark.instrumentation", ""));
  }

  public static class Muzzle extends ReferenceMatcher {}
}
