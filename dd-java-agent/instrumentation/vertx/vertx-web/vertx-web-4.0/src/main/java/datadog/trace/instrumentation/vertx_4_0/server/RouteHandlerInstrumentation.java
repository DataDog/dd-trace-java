package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class RouteHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RouteHandlerInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RouteImpl";
  }

  /** Declared path of each route, as the app wrote it (with its trailing {@code *}). */
  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.vertx.ext.web.Route", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("handler", "blockingHandler"))
            .and(isPublic())
            .and(takesArgument(0, named("io.vertx.core.Handler"))),
        packageName + ".RouteHandlerWrapperAdvice");
    transformer.applyAdvice(
        isMethod().and(named("setPath")).and(takesArguments(1)).and(takesArgument(0, String.class)),
        packageName + ".RouteSetPathAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("setRegex"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        packageName + ".RouteSetRegexAdvice");
  }
}
