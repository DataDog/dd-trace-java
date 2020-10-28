package datadog.trace.instrumentation.reactor.core;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class MonoTerminalOperatorInstrumentation extends Instrumenter.Default {
  public MonoTerminalOperatorInstrumentation() {
    super("reactor-core");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("reactor.core.publisher.MonoPeekTerminal$MonoTerminalPeekSubscriber");
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
        packageName + ".MonoTerminalOperatorAdvices$OnSubscribeAdvice");
    transformers.put(
        isMethod().and(named("onNext").or(named("onError")).or(named("onComplete"))),
        packageName + ".MonoTerminalOperatorAdvices$OnNextAndCompleteAndErrorAdvice");
    return transformers;
  }
}
