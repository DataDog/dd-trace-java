package datadog.trace.instrumentation.micronaut.v2_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class MicronautInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MicronautInstrumentation() {
    super("micronaut", "micronaut-http-server-netty", "micronaut-http-server-netty-2");
  }

  @Override
  public String instrumentedType() {
    return "io.micronaut.http.server.netty.RoutingInBoundHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("channelRead0"))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, named("io.micronaut.http.HttpRequest"))),
        "datadog.trace.instrumentation.micronaut.ChannelRead0Advice");

    transformer.applyAdvice(
        isMethod()
            .and(named("handleRouteMatch"))
            .and(takesArgument(0, named("io.micronaut.web.router.RouteMatch")))
            .and(takesArgument(1, named("io.micronaut.http.server.netty.NettyHttpRequest")))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(3, named("boolean"))),
        "datadog.trace.instrumentation.micronaut.HandleRouteMatchAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("writeFinalNettyResponse"))
            .and(takesArgument(0, named("io.micronaut.http.MutableHttpResponse")))
            .and(takesArgument(1, named("io.micronaut.http.HttpRequest")))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandlerContext"))),
        "datadog.trace.instrumentation.micronaut.WriteFinalNettyResponseAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("writeDefaultErrorResponse"))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, named("io.micronaut.http.server.netty.NettyHttpRequest")))
            .and(takesArgument(2, named("java.lang.Throwable")))
            .and(takesArgument(3, named("boolean"))),
        packageName + ".WriteDefaultErrorResponseAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.micronaut.MicronautDecorator",
    };
  }
}
