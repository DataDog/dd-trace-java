package mule4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Test instrumentation to make the {@code testHandle} method in {@code HttpServerTestHandler} call
 * the {@code testHandle} method in the {@code HttpServerTestHandlerBridge}, that can interact with
 * the test code in {@code HttpServerTest}.
 */
@AutoService(Instrumenter.class)
public class HttpServerTestHandlerInstrumentation extends Instrumenter.Tracing {

  public HttpServerTestHandlerInstrumentation() {
    super("mule4-http-server-test-handler");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("mule4.HttpServerTestHandler");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("testHandle").and(isStatic()), "mule4.HttpServerTestHandlerAdvice");
  }
}
