import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.TestInstrumentation;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.elasticsearch.ShadowExistingScopeAdvice;

/**
 * This instrumentation is needed to break automatic async trace propagation to the embedded
 * Elasticsearch instance. Otherwise, our client instrumentation picks up on non-deterministic
 * behavior that happens inside Elasticsearch (eg IndexAction). It is duplicated several times to
 * each elasticsearch project
 */
@AutoService(InstrumenterModule.class)
public class ElasticsearchBreakTraceInstrumentation extends TestInstrumentation {
  @Override
  public String instrumentedType() {
    return "org.elasticsearch.client.node.NodeClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // this method changed to executeLocally in 5+
    transformer.applyAdvice(named("doExecute"), ShadowExistingScopeAdvice.class.getName());
  }
}
