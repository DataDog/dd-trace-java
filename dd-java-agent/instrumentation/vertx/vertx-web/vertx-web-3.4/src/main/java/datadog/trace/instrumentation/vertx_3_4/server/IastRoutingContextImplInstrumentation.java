package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class IastRoutingContextImplInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String className = IastRoutingContextImplInstrumentation.class.getName();

  public IastRoutingContextImplInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(named("cookies").and(takesArguments(0)), className + "$CookiesAdvice");
    transformer.applyAdvice(
        named("getCookie").and(takesArguments(1)).and(takesArgument(0, String.class)),
        className + "$GetCookieAdvice");
    transformer.applyAdvice(
        named("reroute").and(takesArguments(2)).and(takesArgument(1, String.class)),
        className + "$RerouteAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class CookiesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onCookies(
        @Advice.Return final Set<Object> cookies, @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null && cookies != null && !cookies.isEmpty()) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        for (final Object cookie : cookies) {
          module.taintObject(ctx, cookie, SourceTypes.REQUEST_COOKIE_VALUE);
        }
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetCookieAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onGetCookie(
        @Advice.Return final Object cookie, @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        module.taintObject(ctx, cookie, SourceTypes.REQUEST_COOKIE_VALUE);
      }
    }
  }

  public static class RerouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
    public static void onReroute(@Advice.Argument(1) final String path) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null) {
        module.onRedirect(path);
      }
    }
  }
}
