import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.context.ShadowExistingScopeAdvice;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * This instrumentation is needed to break automatic async trace propagation to the embedded
 * Elasticsearch instance. Otherwise, our client instrumentation picks up on non-deterministic
 * behavior that happens inside Elasticsearch (eg IndexAction). It is duplicated several times to
 * each elasticsearch project
 */
@AutoService(Instrumenter.class)
public class ElasticsearchBreakTraceInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("org.elasticsearch.client.node.NodeClient"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                // this method changed to executeLocally in 5+
                .advice(named("doExecute"), ShadowExistingScopeAdvice.class.getName()));
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    // don't care
    return true;
  }
}
