package datadog.trace.instrumentation.reactor.core;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class TerminalSubscriberInstrumentation extends Instrumenter.Tracing {
  public TerminalSubscriberInstrumentation() {
    super("reactor-core");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(
        "reactor.core.publisher.MonoPeekTerminal$MonoTerminalPeekSubscriber",
        "reactor.core.publisher.FluxPeek$PeekSubscriber");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.reactivestreams.Subscriber", AgentSpan.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(named("onSubscribe")),
        packageName + ".TerminalSubscriberAdvices$OnSubscribeAdvice");
    transformers.put(
        isMethod().and(named("onNext").or(named("onError")).or(named("onComplete"))),
        packageName + ".TerminalSubscriberAdvices$OnNextAndCompleteAndErrorAdvice");
    return transformers;
  }
}
