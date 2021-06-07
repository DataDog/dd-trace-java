package datadog.trace.instrumentation.micronaut;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class MicronautInstrumentation extends Instrumenter.Tracing {

  public MicronautInstrumentation() {
    super("micronaut", "micronaut-http-server-netty", "micronaut-http-server-netty-2");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.micronaut.http.server.netty.RoutingInBoundHandler");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("channelRead0"))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, named("io.micronaut.http.HttpRequest"))),
        packageName + ".ChannelRead0Advice");

    transformation.applyAdvice(
        isMethod()
            .and(named("handleRouteMatch"))
            .and(takesArgument(0, named("io.micronaut.web.router.RouteMatch")))
            .and(takesArgument(1, named("io.micronaut.http.server.netty.NettyHttpRequest")))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(3, named("boolean"))),
        packageName + ".HandleRouteMatchAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("writeDefaultErrorResponse"))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, named("io.micronaut.http.server.netty.NettyHttpRequest")))
            .and(takesArgument(2, named("java.lang.Throwable")))
            .and(takesArgument(3, named("boolean"))),
        packageName + ".WriteDefaultErrorResponseAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("writeFinalNettyResponse"))
            .and(takesArgument(0, named("io.micronaut.http.MutableHttpResponse")))
            .and(takesArgument(1, named("io.micronaut.http.HttpRequest")))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandlerContext"))),
        packageName + ".WriteFinalNettyResponseAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MicronautDecorator",
    };
  }
}
