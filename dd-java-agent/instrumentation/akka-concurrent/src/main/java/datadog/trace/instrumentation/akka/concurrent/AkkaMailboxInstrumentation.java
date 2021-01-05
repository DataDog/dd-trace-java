package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.context.TraceScope;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class AkkaMailboxInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  public AkkaMailboxInstrumentation() {
    super("akka_actor_mailbox", "akka_actor", "akka_concurrent", "java_concurrent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.dispatch.Mailbox");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(named("run")), getClass().getName() + "$SuppressMailboxRunAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    List<String> excludedClass = singletonList("akka.dispatch.MailBox");
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
   * {@code AkkaHttpServerInstrumentation} so that it propagates to the user provided request
   * handling code that will execute on the same thread in the same actor.
   */
  public static final class SuppressMailboxRunAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      // Create our own noopSpan to make sure that we close all scopes up until this
      // position after exit
      AgentSpan span = new AgentTracer.NoopAgentSpan();
      return activateSpan(span, false);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope) {
      // Clean up any leaking scopes from akka-streams/akka-http et.c.
      TraceScope activeScope = activeScope();
      while (activeScope != null && activeScope != scope) {
        activeScope.close();
        activeScope = activeScope();
      }
      while (activeScope == scope) {
        scope.close();
        activeScope = activeScope();
      }
    }
  }
}
