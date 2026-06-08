// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import net.bytebuddy.asm.Advice;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.core.support.RepositoryProxyPostProcessor;

public class RepositoryFactorySupportAdvice {
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
