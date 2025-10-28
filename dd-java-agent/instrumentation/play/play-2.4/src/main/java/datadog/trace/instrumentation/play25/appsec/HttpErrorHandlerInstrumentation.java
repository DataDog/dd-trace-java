package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import play.api.http.HttpErrorHandler;
import play.api.mvc.RequestHeader;

/** @see HttpErrorHandler#onServerError(RequestHeader, Throwable) */
@AutoService(InstrumenterModule.class)
public class HttpErrorHandlerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public HttpErrorHandlerInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play25only";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_25_ONLY;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("onServerError"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("play.api.mvc.RequestHeader")))
            .and(takesArgument(1, Throwable.class))
            .and(returns(named("scala.concurrent.Future"))),
        HttpErrorHandlerInstrumentation.class.getName() + "$OnServerErrorAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "play.api.http.HttpErrorHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("play.api.http.HttpErrorHandler"));
  }

  static class OnServerErrorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(@Advice.Argument(1) Throwable t) {
      int i = CallDepthThreadLocalMap.incrementCallDepth(HttpErrorHandler.class);
      if (i > 0) {
        return;
      }

      if (!(t instanceof BlockingException)) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }
      agentSpan.addThrowable(t);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after() {
      CallDepthThreadLocalMap.decrementCallDepth(HttpErrorHandler.class);
    }
  }
}
