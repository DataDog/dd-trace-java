package datadog.trace.instrumentation.lettuce5;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

@AutoService(Instrumenter.class)
public class LettuceDefaultEndpointInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public LettuceDefaultEndpointInstrumentation() {
    super("lettuce");
  }

  @Override
  public String instrumentedType() {
    return "io.lettuce.core.protocol.DefaultEndpoint";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("write")),
        packageName + ".LettuceDefaultEndpointAdvice");

  }
}
