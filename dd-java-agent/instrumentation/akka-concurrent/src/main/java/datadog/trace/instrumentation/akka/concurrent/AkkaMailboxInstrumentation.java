package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

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
public class AkkaMailboxInstrumentation extends Instrumenter.Default
    implements ExcludeFilterProvider {

  public AkkaMailboxInstrumentation() {
    super("java_concurrent", "akka_concurrent", "akka_actor");
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
