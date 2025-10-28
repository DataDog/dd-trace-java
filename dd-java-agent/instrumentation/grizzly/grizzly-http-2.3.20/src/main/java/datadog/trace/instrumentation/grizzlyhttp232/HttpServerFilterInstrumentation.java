package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;

public class HttpServerFilterInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.HttpServerFilter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("prepareResponse")
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("org.glassfish.grizzly.http.HttpRequestPacket")))
            .and(takesArgument(2, named("org.glassfish.grizzly.http.HttpResponsePacket")))
            .and(takesArgument(3, named("org.glassfish.grizzly.http.HttpContent")))
            .and(isPrivate()),
        "datadog.trace.instrumentation.grizzlyhttp232.HttpServerFilterAdvice");
  }
}
