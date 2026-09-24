package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.dispatch.Envelope;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class AkkaDeadLetterQueueInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AkkaDeadLetterQueueInstrumentation() {
    super("akka_actor_receive", "akka_actor", "akka_concurrent", "java_concurrent");
  }

  @Override
  public String hierarchyMarkerType() {
    return "akka.dispatch.MessageQueue";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Match the dead-letter queue without depending on Scala's anonymous-class numbering.
    return nameStartsWith("akka.dispatch.Mailboxes$")
        .and(implementsInterface(named("akka.dispatch.MessageQueue")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.Envelope", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("enqueue"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("akka.actor.ActorRef")))
            .and(takesArgument(1, named("akka.dispatch.Envelope")))
            .and(returns(void.class)),
        getClass().getName() + "$EnqueueAdvice");
  }

  public static class EnqueueAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterEnqueue(@Advice.Argument(1) Envelope envelope) {
      // Dead-letter publication discards the original envelope without invoking its actor.
      State state = InstrumentationContext.get(Envelope.class, State.class).get(envelope);
      if (state != null) {
        state.closeContinuation();
      }
    }
  }
}
