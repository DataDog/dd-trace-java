package mule4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

/**
 * Test instrumentation to make the {@code testHandle} method in {@code HttpServerTestHandler} call
 * the {@code testHandle} method in the {@code HttpServerTestHandlerBridge}, that can interact with
 * the test code in {@code HttpServerTest}.
 */
@AutoService(Instrumenter.class)
public class HttpServerTestHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public HttpServerTestHandlerInstrumentation() {
    super("mule4-http-server-test-handler");
  }

  @Override
  public String instrumentedType() {
    return "mule4.HttpServerTestHandler";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("testHandle").and(isStatic()), "mule4.HttpServerTestHandlerAdvice");
  }
}
