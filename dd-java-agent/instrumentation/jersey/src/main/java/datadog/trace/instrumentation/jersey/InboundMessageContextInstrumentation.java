package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
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
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class InboundMessageContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public InboundMessageContextInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getHeaders").and(isPublic()).and(takesArguments(0)),
        InboundMessageContextInstrumentation.class.getName() + "$InstrumenterAdviceGetHeaders");

    transformer.applyAdvice(
        named("getRequestCookies").and(isPublic()).and(takesArguments(0)),
        InboundMessageContextInstrumentation.class.getName()
            + "$InstrumenterAdviceGetRequestCookies");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.InboundMessageContext";
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class InstrumenterAdviceGetHeaders {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Return Map<String, List<String>> headers,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop != null && headers != null && !headers.isEmpty()) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
          final String name = entry.getKey();
          prop.taintString(ctx, name, SourceTypes.REQUEST_HEADER_NAME, name);
          for (String value : entry.getValue()) {
            prop.taintString(ctx, value, SourceTypes.REQUEST_HEADER_VALUE, name);
          }
        }
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class InstrumenterAdviceGetRequestCookies {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(
        @Advice.Return Map<String, Object> cookies, @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null && cookies != null && !cookies.isEmpty()) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        for (Map.Entry<String, Object> entry : cookies.entrySet()) {
          final String name = entry.getKey();
          module.taintString(ctx, name, SourceTypes.REQUEST_COOKIE_NAME, name);
          module.taintObject(ctx, entry.getValue(), SourceTypes.REQUEST_COOKIE_VALUE, name);
        }
      }
    }
  }
}
