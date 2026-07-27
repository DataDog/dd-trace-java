package datadog.trace.instrumentation.springdata;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryProxyPostProcessor;

public final class InterceptingRepositoryProxyPostProcessor
    implements RepositoryProxyPostProcessor {
  public static final RepositoryProxyPostProcessor INSTANCE =
      new InterceptingRepositoryProxyPostProcessor();

  @Override
  public void postProcess(
      final ProxyFactory factory, final RepositoryInformation repositoryInformation) {
    factory.addAdvice(0, new RepositoryInterceptor(repositoryInformation.getRepositoryInterface()));
  }
}
