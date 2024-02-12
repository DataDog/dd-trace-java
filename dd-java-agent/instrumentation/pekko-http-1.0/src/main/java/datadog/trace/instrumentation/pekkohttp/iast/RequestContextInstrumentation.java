package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterGroup;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.server.RequestContext;

/** Propagates taint when fetching the {@link HttpRequest} from the {@link RequestContext}. */
@AutoService(Instrumenter.class)
public class RequestContextInstrumentation extends InstrumenterGroup.Iast
    implements Instrumenter.ForSingleType {
  public RequestContextInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.server.RequestContextImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("request"))
            .and(returns(named("org.apache.pekko.http.scaladsl.model.HttpRequest")))
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
      if (propagation == null || propagation.isTainted(request)) {
        return;
      }

      propagation.taintIfTainted(request, requestContext);
    }
  }
}
