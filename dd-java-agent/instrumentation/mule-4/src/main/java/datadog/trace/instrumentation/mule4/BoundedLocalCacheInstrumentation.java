package datadog.trace.instrumentation.mule4;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class BoundedLocalCacheInstrumentation extends Instrumenter.Tracing {

  public BoundedLocalCacheInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.github.benmanes.caffeine.cache.BoundedLocalCache");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("scheduleDrainBuffers").and(takesArguments(0)),
        packageName + ".BoundedLocalCacheAdvice");
  }
}
