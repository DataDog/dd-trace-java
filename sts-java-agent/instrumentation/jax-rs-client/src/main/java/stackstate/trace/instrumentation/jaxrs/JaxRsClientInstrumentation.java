package stackstate.trace.instrumentation.jaxrs;

import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import javax.ws.rs.client.ClientBuilder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;

@AutoService(Instrumenter.class)
public final class JaxRsClientInstrumentation extends Instrumenter.Configurable {

  public JaxRsClientInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-client");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  protected AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(failSafe(hasSuperType(named("javax.ws.rs.client.ClientBuilder"))))
        .transform(
            new HelperInjector(
                "stackstate.trace.instrumentation.jaxrs.ClientTracingFeature",
                "stackstate.trace.instrumentation.jaxrs.ClientTracingFilter"))
        .transform(STSTransformers.defaultTransformers())
        .transform(
            STSAdvice.create()
                .advice(
                    named("build").and(returns(hasSuperType(named("javax.ws.rs.client.Client")))),
                    ClientBuilderAdvice.class.getName()))
        .asDecorator();
  }

  public static class ClientBuilderAdvice {

    @Advice.OnMethodEnter
    public static void registerFeature(@Advice.This final ClientBuilder builder) {
      builder.register(ClientTracingFeature.class);
    }
  }
}
