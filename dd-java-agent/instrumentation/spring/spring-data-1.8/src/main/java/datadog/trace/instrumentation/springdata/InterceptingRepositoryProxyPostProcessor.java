package datadog.trace.instrumentation.springdata;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryProxyPostProcessor;

public final class InterceptingRepositoryProxyPostProcessor
    implements RepositoryProxyPostProcessor {
  public static final RepositoryProxyPostProcessor INSTANCE =
      new InterceptingRepositoryProxyPostProcessor();

  // DQH - TODO: Support older versions?
  // The signature of postProcess changed to add RepositoryInformation in
  // spring-data-commons 1.9.0
  // public void postProcess(final ProxyFactory factory) {
  //   factory.addAdvice(0, RepositoryInterceptor.INSTANCE);
  // }

  @Override
  public void postProcess(
      final ProxyFactory factory, final RepositoryInformation repositoryInformation) {
    factory.addAdvice(0, new RepositoryInterceptor(repositoryInformation.getRepositoryInterface()));
  }
}
