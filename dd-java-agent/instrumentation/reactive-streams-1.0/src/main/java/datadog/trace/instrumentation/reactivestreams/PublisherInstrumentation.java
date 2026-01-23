package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
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

/**
 * This instrumentation is responsible for capturing the span when {@link
 * Publisher#subscribe(Subscriber)} is called. The state is then stored and will be used to
 * eventually propagate on the downstream signals.
 */
@AutoService(InstrumenterModule.class)
public class PublisherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public PublisherInstrumentation() {
    super("reactive-streams", "reactive-streams-1");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.reactivestreams.Publisher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("org.reactivestreams.Publisher"));
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
      this.packageName + ".ReactiveStreamsAsyncResultExtension",
      this.packageName + ".ReactiveStreamsAsyncResultExtension$WrappedPublisher",
      this.packageName + ".ReactiveStreamsAsyncResultExtension$WrappedSubscriber",
      this.packageName + ".ReactiveStreamsAsyncResultExtension$WrappedSubscription",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, hasInterface(named("org.reactivestreams.Subscriber")))),
        getClass().getName() + "$PublisherSubscribeAdvice");
  }

  public static class PublisherSubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onSubscribe(
        @Advice.This final Publisher self, @Advice.Argument(value = 0) final Subscriber s) {

      final AgentSpan span =
          InstrumentationContext.get(Publisher.class, AgentSpan.class).remove(self);
      final AgentSpan activeSpan = activeSpan();
      if (s == null || (span == null && activeSpan == null)) {
        return null;
      }
      final AgentSpan current =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class)
              .putIfAbsent(s, span != null ? span : activeSpan);
      if (current != null) {
        return activateSpan(current);
      }

      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterSubscribe(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
