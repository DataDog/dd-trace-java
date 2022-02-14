// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.core.support.RepositoryProxyPostProcessor;

@AutoService(Instrumenter.class)
public final class SpringRepositoryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor(),
        SpringRepositoryInstrumentation.class.getName() + "$RepositoryFactorySupportAdvice");
  }

  public static class RepositoryFactorySupportAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruction(
        @Advice.This final RepositoryFactorySupport repositoryFactorySupport) {
      repositoryFactorySupport.addRepositoryProxyPostProcessor(
          InterceptingRepositoryProxyPostProcessor.INSTANCE);
    }

    // Muzzle doesn't detect the "Override" implementation dependency, so we have to help it.
    private void muzzleCheck(final RepositoryProxyPostProcessor processor) {
      processor.postProcess(null, null);
      // (see usage in InterceptingRepositoryProxyPostProcessor below)
    }
  }
}
