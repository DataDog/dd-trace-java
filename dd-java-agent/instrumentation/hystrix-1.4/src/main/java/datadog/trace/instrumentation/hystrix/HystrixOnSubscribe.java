package datadog.trace.instrumentation.hystrix;

import static datadog.trace.instrumentation.hystrix.HystrixDecorator.DECORATE;

import com.netflix.hystrix.HystrixInvokableInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.rxjava.TracedOnSubscribe;
import rx.Observable;

public class HystrixOnSubscribe extends TracedOnSubscribe {
  private static final String OPERATION_NAME = "hystrix.cmd";

  private final HystrixInvokableInfo<?> command;
  private final String methodName;

  public HystrixOnSubscribe(
      final Observable originalObservable,
      final HystrixInvokableInfo<?> command,
      final String methodName) {
    super(originalObservable, OPERATION_NAME, DECORATE);

    this.command = command;
    this.methodName = methodName;
  }

  @Override
  protected void afterStart(final AgentSpan span) {
    super.afterStart(span);

    DECORATE.onCommand(span, command, methodName);
  }
}
