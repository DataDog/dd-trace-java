package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.reactor.core.ContextSpanHelper.extractSpanFromSubscriberContext;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.CoreSubscriber;

@AutoService(InstrumenterModule.class)
public class CorePublisherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public CorePublisherInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.CoreSubscriber";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("reactor.core.CorePublisher")) // from 3.1.7
        .or(
            hasSuperType(
                namedOneOf(
                    "reactor.core.publisher.Mono", "reactor.core.publisher.Flux"))); // < 3.1.7
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", AgentSpan.class.getName());
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ContextSpanHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("subscribe")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, named("reactor.core.CoreSubscriber"))),
        getClass().getName() + "$PropagateContextSpanOnSubscribe");
  }

  public static class PropagateContextSpanOnSubscribe {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This Publisher<?> self, @Advice.Argument(0) final CoreSubscriber<?> subscriber) {
      final AgentSpan span = extractSpanFromSubscriberContext(subscriber);

      if (span != null) {
        // we force storing the span state linked to publisher and subscriber to the one explicitly
        // present in the context so that, if PublisherInstrumentation is kicking in after this
        // advice, it won't override that active span
        InstrumentationContext.get(Publisher.class, AgentSpan.class).put(self, span);
        InstrumentationContext.get(Subscriber.class, AgentSpan.class).put(subscriber, span);
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
