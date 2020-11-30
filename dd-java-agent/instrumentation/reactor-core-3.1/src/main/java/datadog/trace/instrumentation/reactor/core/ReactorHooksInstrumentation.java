package datadog.trace.instrumentation.reactor.core;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ReactorHooksInstrumentation extends Instrumenter.Default {

  public ReactorHooksInstrumentation() {
    super("reactor-core");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("reactor.core.publisher.Hooks");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingOperator",
      packageName + ".TracingOperator$Lifter",
      packageName + ".TracingOperator$LifterFilter",
      packageName + ".TracingSubscriber",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer().or(named("resetOnEachOperator")), packageName + ".ReactorHooksAdvice");
  }
}
