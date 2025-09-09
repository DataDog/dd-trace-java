package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
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
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ServerServletHttpRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ServerServletHttpRequestInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.http.server.ServletServerHttpRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("getHeaders")).and(takesArguments(0)),
        getClass().getName() + "$TaintHeadersAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class TaintHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.Return Object object, @ActiveRequestContext RequestContext reqCtx) {
      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      propagation.taintObject(ctx, object, SourceTypes.REQUEST_HEADER_VALUE);
    }
  }
}
