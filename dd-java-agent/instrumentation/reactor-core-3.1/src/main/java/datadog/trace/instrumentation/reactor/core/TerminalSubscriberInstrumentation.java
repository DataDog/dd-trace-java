package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;

@AutoService(Instrumenter.class)
public class TerminalSubscriberInstrumentation extends Instrumenter.Tracing
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
    return singletonMap("org.reactivestreams.Subscriber", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onSubscribe")),
        packageName + ".TerminalSubscriberAdvices$OnSubscribeAdvice");
    transformer.applyAdvice(
        isMethod().and(namedOneOf("onNext", "onError", "onComplete")),
        packageName + ".TerminalSubscriberAdvices$OnNextAndCompleteAndErrorAdvice");
  }
}
