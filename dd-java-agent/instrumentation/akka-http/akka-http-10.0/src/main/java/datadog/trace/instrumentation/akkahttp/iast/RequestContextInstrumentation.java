package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.server.RequestContext;
import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;

/** Propagates taint when fetching the {@link HttpRequest} from the {@link RequestContext}. */
@AutoService(InstrumenterModule.class)
public class RequestContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RequestContextInstrumentation() {
    super("akka-http");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.RequestContextImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("request"))
            .and(returns(named("akka.http.scaladsl.model.HttpRequest")))
            .and(takesArguments(0)),
        RequestContextInstrumentation.class.getName() + "$GetRequestAdvice");
  }

  @SuppressFBWarnings("BC_IMPOSSIBLE_INSTANCEOF")
  @RequiresRequestContext(RequestContextSlot.IAST)
  static class GetRequestAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    static void onExit(
        @Advice.This RequestContext requestContext,
        @Advice.Return HttpRequest request,
        @ActiveRequestContext datadog.trace.api.gateway.RequestContext reqCtx) {

      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null) {
        return;
      }

      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);

      if (propagation.isTainted(ctx, request)) {
        return;
      }

      propagation.taintObjectIfTainted(ctx, request, requestContext);
    }
  }
}
