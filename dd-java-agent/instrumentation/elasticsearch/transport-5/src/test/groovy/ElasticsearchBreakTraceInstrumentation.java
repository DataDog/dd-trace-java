import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.TestInstrumentation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.elasticsearch.ShadowExistingScopeAdvice;

/**
 * This instrumentation is needed to break automatic async trace propagation to the embedded
 * Elasticsearch instance. Otherwise, our client instrumentation picks up on non-deterministic
 * behavior that happens inside Elasticsearch (eg IndexAction). It is duplicated several times to
 * each elasticsearch project
 */
@AutoService(Instrumenter.class)
public class ElasticsearchBreakTraceInstrumentation extends TestInstrumentation {
  @Override
  public String instrumentedType() {
    return "org.elasticsearch.client.node.NodeClient";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(named("executeLocally"), ShadowExistingScopeAdvice.class.getName());
  }
}
