package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(Instrumenter.class)
public class RouteHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public RouteHandlerInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".EndHandlerWrapper",
      packageName + ".RouteHandlerWrapper",
      packageName + ".VertxDecorator",
      packageName + ".VertxDecorator$VertxURIDataAdapter",
    };
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RouteImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("handler"))
            .and(isPublic())
            .and(takesArgument(0, named("io.vertx.core.Handler"))),
        packageName + ".RouteHandlerWrapperAdvice");
  }
}
