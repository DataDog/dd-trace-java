package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
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
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

@AutoService(InstrumenterModule.class)
public class PublisherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

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
    ret.put("org.reactivestreams.Subscriber", PublisherState.class.getName());
    ret.put("org.reactivestreams.Publisher", PublisherState.class.getName());
    return ret;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      this.packageName + ".ReactiveStreamsAsyncResultSupportExtension",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), this.getClass().getName() + "$PublisherAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, hasInterface(named("org.reactivestreams.Subscriber")))),
        getClass().getName() + "$PublisherSubscribeAdvice");
  }

  public static class PublisherAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void init() {
      ReactiveStreamsAsyncResultSupportExtension.initialize(
          InstrumentationContext.get(Publisher.class, PublisherState.class));
    }
  }

  public static class PublisherSubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onSubscribe(
        @Advice.This final Publisher self, @Advice.Argument(value = 0) final Subscriber s) {
      PublisherState publisherState =
          InstrumentationContext.get(Publisher.class, PublisherState.class).remove(self);
      if (publisherState == null) {
        publisherState = new PublisherState();
      }
      AgentSpan span = publisherState.getSubscriptionSpan();
      AgentSpan active = activeSpan();
      InstrumentationContext.get(Subscriber.class, PublisherState.class)
          .put(
              s,
              publisherState.withSubscriptionSpan(
                  span == null ? (active != null ? active : noopSpan()) : span));
      if (span != null) {
        return activateSpan(span);
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
