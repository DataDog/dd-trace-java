package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation make possible capturing the current span (or the absence of it by nopping)
 * when peek methods are called (doOnXXX, doAfterXX, log, peek, etc...)
 */
@AutoService(InstrumenterModule.class)
public class TerminalSubscriberInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes {
  public TerminalSubscriberInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "reactor.core.publisher.MonoPeekTerminal$MonoTerminalPeekSubscriber",
      "reactor.core.publisher.FluxPeek$PeekSubscriber"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.reactivestreams.Subscriber", PublisherState.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onSubscribe")), getClass().getName() + "$OnSubscribeAdvice");
  }

  public static class OnSubscribeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This Subscriber self) {
      AgentSpan active = AgentTracer.activeSpan();
      InstrumentationContext.get(Subscriber.class, PublisherState.class)
          .putIfAbsent(self, PublisherState::new)
          .withSubscriptionSpan(active != null ? active : AgentTracer.noopSpan());
    }
  }
}
