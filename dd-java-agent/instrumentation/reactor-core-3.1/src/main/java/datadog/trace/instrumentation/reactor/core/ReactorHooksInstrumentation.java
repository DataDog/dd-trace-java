package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class ReactorHooksInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ReactorHooksInstrumentation() {
    super("reactor-hooks");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "reactor.core.publisher.Hooks";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingOperator", packageName + ".TracingSubscriber",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isTypeInitializer().or(named("resetOnEachOperator")), packageName + ".ReactorHooksAdvice");
  }
}
