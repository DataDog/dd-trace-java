package datadog.trace.instrumentation.dubbo_3_2;


import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.dubbo.rpc.RpcInvocation;

import static datadog.trace.instrumentation.dubbo_3_2.DubboDecorator.DECORATE;

public class DubboServerCallAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.FieldValue("invocation") RpcInvocation invocation) {
    return DECORATE.serverCall(invocation);
  }
//
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,@Advice.FieldValue("invocation") RpcInvocation invocation, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATE.onError(scope.span(), throwable);
    DECORATE.beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }

}
