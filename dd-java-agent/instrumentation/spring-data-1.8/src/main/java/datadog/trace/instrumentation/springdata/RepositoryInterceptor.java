package datadog.trace.instrumentation.springdata;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springdata.SpringDataDecorator.DECORATOR;
import static datadog.trace.instrumentation.springdata.SpringDataDecorator.REPOSITORY_OPERATION;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.data.repository.Repository;

final class RepositoryInterceptor implements MethodInterceptor {
  private final Class<?> repositoryInterface;

  RepositoryInterceptor(Class<?> repositoryInterface) {
    this.repositoryInterface = repositoryInterface;
  }

  @Override
  public Object invoke(final MethodInvocation methodInvocation) throws Throwable {
    final Method invokedMethod = methodInvocation.getMethod();
    final Class<?> clazz = invokedMethod.getDeclaringClass();

    final boolean isRepositoryOp = Repository.class.isAssignableFrom(clazz);
    // Since this interceptor is the outer most interceptor, non-Repository methods
    // including Object methods will also flow through here.  Don't create spans for those.
    if (!isRepositoryOp) {
      return methodInvocation.proceed();
    }

    final AgentSpan span = startSpan(REPOSITORY_OPERATION);
    DECORATOR.afterStart(span);
    DECORATOR.onOperation(span, invokedMethod, repositoryInterface);

    final AgentScope scope = activateSpan(span);

    Object result = null;
    try {
      result = methodInvocation.proceed();
    } catch (final Throwable t) {
      DECORATOR.onError(scope, t);
      throw t;
    } finally {
      DECORATOR.beforeFinish(scope);
      scope.close();
      span.finish();
    }
    return result;
  }
}
