package mule4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Test instrumentation to make the {@code testHandle} method in {@code HttpServerTestHandler} call
 * the {@code testHandle} method in the {@code HttpServerTestHandlerBridge}, that can interact with
 * the test code in {@code HttpServerTest}.
 */
@AutoService(InstrumenterModule.class)
public class HttpServerTestHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HttpServerTestHandlerInstrumentation() {
    super("mule4-http-server-test-handler");
  }

  @Override
  public String instrumentedType() {
    return "mule4.HttpServerTestHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("testHandle").and(isStatic()), "mule4.HttpServerTestHandlerAdvice");
  }
}
