package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class InvocableHandlerMethodInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public InvocableHandlerMethodInstrumentation() {
    super("spring-web");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.method.support.InvocableHandlerMethod";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("invokeForRequest"), packageName + ".WrapContinuableResultAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebHttpServerDecorator", packageName + ".ServletRequestURIAdapter",
    };
  }
}
