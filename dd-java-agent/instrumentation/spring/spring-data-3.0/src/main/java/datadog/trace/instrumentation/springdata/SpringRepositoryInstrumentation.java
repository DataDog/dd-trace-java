// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class SpringRepositoryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SpringRepositoryInstrumentation() {
    super("spring-data");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.data.repository.core.support.RepositoryFactorySupport";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringDataDecorator",
      packageName + ".RepositoryInterceptor",
      packageName + ".InterceptingRepositoryProxyPostProcessor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".RepositoryFactorySupportAdvice");
  }
}
