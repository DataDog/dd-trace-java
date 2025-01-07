package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import io.vertx.ext.web.impl.RoutingContextImpl;
import net.bytebuddy.asm.Advice;

/**
 * @see RoutingContextImpl#getBodyAsJson(int)
 * @see RoutingContextImpl#getBodyAsJsonArray(int)
 */
@AutoService(InstrumenterModule.class)
public class IastRoutingContextImplInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public IastRoutingContextImplInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("reroute").and(takesArguments(2)).and(takesArgument(1, String.class)),
        IastRoutingContextImplInstrumentation.class.getName() + "$RerouteAdvice");
  }

  public static class RerouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onReroute(@Advice.Argument(1) final String path) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null) {
        module.onRedirect(path);
      }
    }
  }
}
