package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(InstrumenterModule.class)
public class RequestBodyInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public RequestBodyInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RequestBodyImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("asJsonObject")
            .or(named("asJsonArray"))
            .and(takesArguments(1))
            .and(takesArgument(0, int.class)),
        packageName + ".RoutingContextJsonAdvice");
  }
}
