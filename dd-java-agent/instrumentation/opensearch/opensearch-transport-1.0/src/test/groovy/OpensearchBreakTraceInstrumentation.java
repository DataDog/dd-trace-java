import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.TestInstrumentation;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.opensearch.ShadowExistingScopeAdvice;

@AutoService(InstrumenterModule.class)
public class OpensearchBreakTraceInstrumentation extends TestInstrumentation {
  @Override
  public String instrumentedType() {
    return "org.opensearch.client.node.NodeClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("executeLocally"), ShadowExistingScopeAdvice.class.getName());
  }
}
