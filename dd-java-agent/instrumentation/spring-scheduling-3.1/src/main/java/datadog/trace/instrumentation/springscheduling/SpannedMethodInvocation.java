package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;

public class SpannedMethodInvocation implements MethodInvocation {

  private final AgentScope.Continuation continuation;
  private final MethodInvocation delegate;

  public SpannedMethodInvocation(AgentScope.Continuation continuation, MethodInvocation delegate) {
    this.continuation = continuation;
    this.delegate = delegate;
  }

  @Override
  public Method getMethod() {
    return delegate.getMethod();
  }

  @Override
  public Object[] getArguments() {
    return delegate.getArguments();
  }

  @Override
  public Object proceed() throws Throwable {
    CharSequence spanName = DECORATE.spanNameForMethod(delegate.getMethod());
    if (continuation != noopContinuation()) {
      return invokeWithContinuation(spanName);
    } else {
      return invokeWithSpan(spanName);
    }
  }

  private Object invokeWithContinuation(CharSequence spanName) throws Throwable {
    try (AgentScope scope = continuation.activate()) {
      return invokeWithSpan(spanName);
    }
  }

  private Object invokeWithSpan(CharSequence spanName) throws Throwable {
    AgentSpan span = startSpan(spanName);
    try (AgentScope scope = activateSpan(span)) {
      return delegate.proceed();
    } finally {
      span.finish();
    }
  }

  @Override
  public Object getThis() {
    return delegate.getThis();
  }

  @Override
  public AccessibleObject getStaticPart() {
    return delegate.getStaticPart();
  }
}
