package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpServerFilterInstrumentation extends Instrumenter.Tracing {

  public HttpServerFilterInstrumentation() {
    super("grizzly-filterchain");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.glassfish.grizzly.http.HttpServerFilter");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrizzlyDecorator",
      packageName + ".HTTPRequestPacketURIDataAdapter",
      packageName + ".ExtractAdapter"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("prepareResponse")
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("org.glassfish.grizzly.http.HttpRequestPacket")))
            .and(takesArgument(2, named("org.glassfish.grizzly.http.HttpResponsePacket")))
            .and(takesArgument(3, named("org.glassfish.grizzly.http.HttpContent")))
            .and(isPrivate()),
        packageName + ".HttpServerFilterAdvice");
  }
}
