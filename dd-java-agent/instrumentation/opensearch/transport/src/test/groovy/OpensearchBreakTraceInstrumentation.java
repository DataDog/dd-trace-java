import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.TestInstrumentation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.opensearch.ShadowExistingScopeAdvice;

@AutoService(Instrumenter.class)
public class OpensearchBreakTraceInstrumentation extends TestInstrumentation {
  @Override
  public String instrumentedType() {
    return "org.opensearch.client.node.NodeClient";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(named("executeLocally"), ShadowExistingScopeAdvice.class.getName());
  }
}
