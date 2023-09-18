package datadog.trace.instrumentation.pekko.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class PekkoMailboxInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, ExcludeFilterProvider {

  public PekkoMailboxInstrumentation() {
    super("pekko_actor_mailbox", "pekko_actor", "pekko_concurrent", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.dispatch.Mailbox";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("run")), getClass().getName() + "$SuppressMailboxRunAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    List<String> excludedClass = singletonList("org.apache.pekko.dispatch.MailBox");
    EnumMap<ExcludeFilter.ExcludeType, Collection<String>> excludedTypes =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    excludedTypes.put(ExcludeFilter.ExcludeType.RUNNABLE, excludedClass);
    excludedTypes.put(ExcludeFilter.ExcludeType.FORK_JOIN_TASK, excludedClass);
    return excludedTypes;
  }

  /**
   * This instrumentation is defensive and closes all scopes on the scope stack that were not there
   * when we started processing this actor mailbox. The reason for that is twofold.
   *
   * <p>1) An actor is self contained, and driven by a thread that could serve many other purposes,
   * and a scope should not leak out after a mailbox has been processed.
   *
   * <p>2) We rely on this cleanup mechanism to be able to intentionally leak the scope in the
   * {@code PekkoHttpServerInstrumentation} so that it propagates to the user provided request
   * handling code that will execute on the same thread in the same actor.
   */
  public static final class SuppressMailboxRunAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      AgentScope activeScope = activeScope();
      // If there is no active scope, we can clean all the way to the bottom
      if (null == activeScope) {
        return null;
      }
      // If there is a noop span in the active scope, we can clean all the way to this scope
      if (activeSpan() instanceof AgentTracer.NoopAgentSpan) {
        return activeScope;
      }
      // Create an active scope with a noop span, and clean all the way to the previous scope
      activateSpan(AgentTracer.NoopAgentSpan.INSTANCE, false);
      return activeScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope) {
      // Clean up any leaking scopes from pekko-streams/pekko-http et.c.
      AgentScope activeScope = activeScope();
      while (activeScope != null && activeScope != scope) {
        activeScope.close();
        activeScope = activeScope();
      }
    }
  }
}
