package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.jersey.JerseyTaintHelper.taintMap;
import static datadog.trace.instrumentation.jersey.JerseyTaintHelper.taintMultiValuedMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.glassfish.jersey.message.internal.InboundMessageContext;

@AutoService(InstrumenterModule.class)
public class InboundMessageContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public InboundMessageContextInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String baseName = InboundMessageContextInstrumentation.class.getName();
    transformer.applyAdvice(
        namedOneOf("header", "headers", "remove").and(returns(named(instrumentedType()))),
        baseName + "$SetHeadersAdvice");
    transformer.applyAdvice(
        named("getHeaders").and(isPublic()).and(takesArguments(0)), baseName + "$GetHeadersAdvice");
    transformer.applyAdvice(
        named("getRequestCookies").and(isPublic()).and(takesArguments(0)),
        baseName + "$CookiesAdvice");
    transformer.applyAdvice(
        named("readEntity").and(isPublic()).and(takesArguments(4)), baseName + "$ReadEntityAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.InboundMessageContext";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JerseyTaintHelper",
    };
  }

  /** This advice tries to skip tainting the headers before they are ready */
  public static class SetHeadersAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(InboundMessageContext.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
      CallDepthThreadLocalMap.decrementCallDepth(InboundMessageContext.class);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Return Map<String, List<String>> headers,
        @ActiveRequestContext RequestContext reqCtx) {
      // ignore internal calls populating headers
      if (CallDepthThreadLocalMap.getCallDepth(InboundMessageContext.class) != 0) {
        return;
      }

      if (headers == null || headers.isEmpty()) {
        return;
      }
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      if (prop.isTainted(ctx, headers)) {
        return;
      }
      prop.taintObject(headers, SourceTypes.REQUEST_HEADER_VALUE);
      taintMultiValuedMap(ctx, prop, SourceTypes.REQUEST_HEADER_VALUE, headers);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class CookiesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(
        @Advice.Return Map<String, Object> cookies, @ActiveRequestContext RequestContext reqCtx) {
      if (cookies == null || cookies.isEmpty()) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      for (final Map.Entry<String, Object> entry : cookies.entrySet()) {
        module.taintObject(ctx, entry.getValue(), SourceTypes.REQUEST_COOKIE_VALUE, entry.getKey());
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class ReadEntityAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    public static void onExit(
        @Advice.Return Object result, @ActiveRequestContext RequestContext reqCtx) {
      if (result == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      if (module.isTainted(ctx, result)) {
        return;
      }
      module.taintObject(ctx, result, SourceTypes.REQUEST_BODY);
      if (result instanceof Map) {
        taintMap(ctx, module, SourceTypes.REQUEST_PARAMETER_VALUE, (Map<?, ?>) result);
      }
    }
  }
}
