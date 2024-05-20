package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class MicronautInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes {

  public MicronautInstrumentation() {
    super("micronaut", "micronaut-http-server-netty", "micronaut-http-server-netty-4");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.micronaut.http.server.netty.RoutingInBoundHandler",
      "io.micronaut.http.server.RouteExecutor",
      "io.micronaut.http.server.netty.NettyRequestLifecycle",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("handleNormal")).and(takesNoArguments()),
        packageName + ".ChannelAcceptAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("handleNormal"))
            .and(takesArgument(0, named("io.micronaut.http.server.netty.NettyHttpRequest"))),
        packageName + ".ChannelAcceptAdvice2");

    transformer.applyAdvice(
        isMethod()
            .and(named("findRouteMatch"))
            .and(takesArgument(0, named("io.micronaut.http.HttpRequest")))
            .and(returns(named("io.micronaut.web.router.UriRouteMatch"))),
        packageName + ".HandleRouteMatchAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("createDefaultErrorResponse"))
            .and(takesArgument(0, named("io.micronaut.http.HttpRequest")))
            .and(takesArgument(1, named("java.lang.Throwable"))),
        packageName + ".CreateDefaultErrorResponseAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("encodeHttpResponse"))
            .and(takesArgument(1, named("io.micronaut.http.server.netty.NettyHttpRequest")))
            .and(
                takesArgument(
                    2,
                    namedOneOf(
                        "io.micronaut.http.MutableHttpResponse",
                        "io.micronaut.http.HttpResponse"))),
        packageName + ".EncodeHttpResponseAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MicronautDecorator",
    };
  }
}
