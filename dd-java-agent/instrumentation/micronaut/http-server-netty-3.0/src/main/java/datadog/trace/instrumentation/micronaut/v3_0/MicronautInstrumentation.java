package datadog.trace.instrumentation.micronaut.v3_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class MicronautInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public MicronautInstrumentation() {
    super("micronaut", "micronaut-http-server-netty", "micronaut-http-server-netty-3");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.micronaut.http.server.netty.RoutingInBoundHandler",
      "io.micronaut.http.server.RouteExecutor"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("channelRead0"))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, named("io.micronaut.http.HttpRequest"))),
        "datadog.trace.instrumentation.micronaut.ChannelRead0Advice");

    transformation.applyAdvice(
        isMethod()
            .and(named("handleRouteMatch"))
            .and(takesArgument(0, named("io.micronaut.web.router.RouteMatch")))
            .and(takesArgument(1, named("io.micronaut.http.server.netty.NettyHttpRequest")))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandlerContext"))),
        "datadog.trace.instrumentation.micronaut.HandleRouteMatchAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("writeFinalNettyResponse"))
            .and(takesArgument(0, named("io.micronaut.http.MutableHttpResponse")))
            .and(takesArgument(1, named("io.micronaut.http.HttpRequest")))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandlerContext"))),
        "datadog.trace.instrumentation.micronaut.WriteFinalNettyResponseAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("createDefaultErrorResponse"))
            .and(takesArgument(0, named("io.micronaut.http.HttpRequest")))
            .and(takesArgument(1, named("java.lang.Throwable"))),
        packageName + ".CreateDefaultErrorResponseAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.micronaut.MicronautDecorator",
    };
  }
}
