package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;

public class SpannedMethodInvocation implements MethodInvocation {

  private final AgentSpan parent;
  private final MethodInvocation delegate;

  public SpannedMethodInvocation(AgentSpan parent, MethodInvocation delegate) {
    this.parent = parent;
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
    final AgentSpan span =
        parent == null ? startSpan(spanName) : startSpan(spanName, parent.context());
    try (AgentScope scope = activateSpan(span)) {
      // question: is this necessary? What does it do?
      // if the delegate does async work is everything OK because of this?
      // if the delegate does async work, should I need to worry about it here?
      scope.setAsyncPropagation(true);
      return delegate.proceed();
    } finally {
      // question: Why can't this just be AutoCloseable? Dogma?
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
