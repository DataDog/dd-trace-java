package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import akka.dispatch.Envelope;
import akka.routing.RoutedActorCell;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class AkkaRoutedActorCellInstrumentation extends Instrumenter.Tracing {

  public AkkaRoutedActorCellInstrumentation() {
    super("akka_actor_send", "akka_actor", "akka_concurrent", "java_concurrent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.routing.RoutedActorCell");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.Envelope", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(
                named("sendMessage")
                    .and(ElementMatchers.takesArgument(0, named("akka.dispatch.Envelope")))),
        getClass().getName() + "$SendMessageAdvice");
  }

  /**
   * RoutedActorCell will sometimes deconstruct the Envelope in the {@code sendMessage} method, so
   * we might need to activate the {@code Scope} to ensure that it propagates properly.
   */
  public static class SendMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope enter(
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
    public static void exit(@Advice.Enter TraceScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }
}
