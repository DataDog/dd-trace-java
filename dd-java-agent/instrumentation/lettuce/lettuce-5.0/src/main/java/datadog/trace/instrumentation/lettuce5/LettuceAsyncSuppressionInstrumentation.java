package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.async.AsyncPropagationSuppressingInstrumentation;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Prevents connection initialization from retaining the active request context. */
@AutoService(InstrumenterModule.class)
public final class LettuceAsyncSuppressionInstrumentation
    extends AsyncPropagationSuppressingInstrumentation implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "io.lettuce.core.protocol.RedisHandshakeHandler";
  }

  @Override
  protected ElementMatcher<? super MethodDescription> suppressedMethods() {
    return named("channelRegistered");
  }
}
