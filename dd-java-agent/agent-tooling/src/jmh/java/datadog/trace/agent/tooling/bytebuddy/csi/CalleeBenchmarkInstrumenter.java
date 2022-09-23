package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.Set;

public class CalleeBenchmarkInstrumenter extends Instrumenter.Default
    implements Instrumenter.ForSingleType {

  public CalleeBenchmarkInstrumenter() {
    super("callee");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.connector.Request";
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getParameter").and(takesArguments(String.class)).and(returns(String.class)),
        CallSiteBenchmarkHelper.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {CallSiteBenchmarkHelper.class.getName()};
  }

  @Override
  public boolean isEnabled() {
    return "callee".equals(System.getProperty("dd.benchmark.instrumentation", ""));
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return true;
  }
}
