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
public final class FluxAndMonoInstrumentation extends Instrumenter.Default {

  public FluxAndMonoInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ReactorHooksAdvice",
      packageName + ".ReactorHooksAdvice$TracingSubscriber",
      packageName + ".ReactorHooksAdvice$TracingSubscriber$NoopTraceScope"
    };
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("reactor.core.publisher.Hooks");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer(),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".ReactorHooksAdvice");
  }
}
