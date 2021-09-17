package datadog.trace.instrumentation.mule4;

import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Events in Mule have an {@code EventContext} attached to them, that travels with the event through
 * the system. We attach the active span to the concrete implementation of the {@code EventContext}
 * and activate/deactivate the span when mule changes which event it is processing.
 */
@AutoService(Instrumenter.class)
public final class HttpRequesterInstrumentation extends Instrumenter.Tracing {

  public HttpRequesterInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.mule.extension.http.internal.request.HttpRequester");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isPrivate().and(named("doRequestWithRetry")),
        packageName + ".HttpRequesterDoRequestAdvice");
  }
}
