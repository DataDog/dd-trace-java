package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Prevents continuations being captured for the channel's idle timer. */
@AutoService(Instrumenter.class)
public class ManagedChannelImplInstrumentation extends Instrumenter.Tracing {
  public ManagedChannelImplInstrumentation() {
    super("grpc", "grpc-server");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return nameEndsWith("io.grpc.internal.ManagedChannelImpl");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("rescheduleIdleTimer")),
        getClass().getName() + "$DisableAsyncPropagation");
  }

  public static final class DisableAsyncPropagation {
    @Advice.OnMethodEnter
    public static boolean before() {
      TraceScope scope = activeScope();
      if (null != scope) {
        boolean wasEnabled = scope.isAsyncPropagating();
        scope.setAsyncPropagation(false);
        return wasEnabled;
      }
      return false;
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter boolean wasEnabled) {
      if (wasEnabled) {
        TraceScope scope = activeScope();
        if (null != scope) {
          scope.setAsyncPropagation(wasEnabled);
        }
      }
    }
  }
}
