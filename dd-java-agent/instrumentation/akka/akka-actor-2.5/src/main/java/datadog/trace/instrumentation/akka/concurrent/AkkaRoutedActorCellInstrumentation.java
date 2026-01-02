package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.dispatch.Envelope;
import akka.routing.RoutedActorCell;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice;

public class AkkaRoutedActorCellInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "akka.routing.RoutedActorCell";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("sendMessage").and(takesArgument(0, named("akka.dispatch.Envelope")))),
        getClass().getName() + "$SendMessageAdvice");
  }

  /**
   * RoutedActorCell will sometimes deconstruct the Envelope in the {@code sendMessage} method, so
   * we might need to activate the {@code Scope} to ensure that it propagates properly.
   */
  public static class SendMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.This RoutedActorCell zis, @Advice.Argument(value = 0) Envelope envelope) {
      // If this isn't a management message, it will be deconstructed before being routed through
      // the routing logic, so activate the Scope
      if (!zis.routerConfig().isManagementMessage(envelope.message())) {
        return AdviceUtils.startTaskScope(
            InstrumentationContext.get(Envelope.class, State.class), envelope);
      }

      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
        // then we have invoked an Envelope and need to mark the work complete
      }
    }
  }
}
