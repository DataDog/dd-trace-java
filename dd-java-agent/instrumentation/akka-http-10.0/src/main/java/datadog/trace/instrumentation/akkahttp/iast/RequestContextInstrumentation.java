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
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;

/** Propagates taint when fetching the {@link HttpRequest} from the {@link RequestContext}. */
@AutoService(Instrumenter.class)
public class RequestContextInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public RequestContextInstrumentation() {
    super("akka-http");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.RequestContextImpl";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("request"))
            .and(returns(named("akka.http.scaladsl.model.HttpRequest")))
            .and(takesArguments(0)),
        RequestContextInstrumentation.class.getName() + "$GetRequestAdvice");
  }

  @SuppressFBWarnings("BC_IMPOSSIBLE_INSTANCEOF")
  static class GetRequestAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    static void onExit(
        @Advice.This RequestContext requestContext, @Advice.Return HttpRequest request) {

      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null
          || !(requestContext instanceof Taintable)
          || !((Object) request instanceof Taintable)
          || ((Taintable) (Object) request).$DD$isTainted()) {
        return;
      }

      propagation.taintIfInputIsTainted(request, requestContext);
    }
  }
}
