import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.HttpServerTestAdvice;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.agent.builder.AgentBuilder;

import static net.bytebuddy.matcher.ElementMatchers.named;

@AutoService(Instrumenter.class)
public class MuleHttpServerTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("org.glassfish.grizzly.http.HttpServerFilter"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .advice(
                    named("handleRead"), HttpServerTestAdvice.ServerEntryAdvice.class.getName()));
  }
}
