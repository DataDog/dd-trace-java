package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;

import static datadog.trace.instrumentation.dubbo_2_7x.DubboDecorator.DECORATE;

public class DubboProviderAdvice {


  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(@Advice.This AbstractProxyInvoker invoker,
                                        @Advice.Argument(0) final Invocation invocation
  ) {
    AgentScope scope = DECORATE.buildSpan(invoker, invocation);
    return scope;
//    return activeScope();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Return Result result, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATE.buildResult(scope, result);
    DECORATE.onError(result,scope.span(), throwable);
    DECORATE.beforeFinish(scope.span());

    scope.close();
    scope.span().finish();
  }
}
