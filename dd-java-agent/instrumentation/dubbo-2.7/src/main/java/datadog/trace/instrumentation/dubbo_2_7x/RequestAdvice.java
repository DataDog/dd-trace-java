package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;

import static datadog.trace.instrumentation.dubbo_2_7x.DubboDecorator.DECORATE;

public class RequestAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(@Advice.This Filter filter,@Advice.Argument(0) final Invoker invoker,
                                        @Advice.Argument(1) final Invocation invocation) {

    System.out.println(filter.getClass().getName());
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(RpcContext.class);
//    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(RpcContext.class);
    if (callDepth > 0) {
      return null;
    }
//    if (filter instanceof ConsumerContextFilter || filter instanceof ContextFilter){
//      return DECORATE.buildSpan(invoker,invocation);
//    }
    AgentScope agentScope = DECORATE.buildSpan(invoker, invocation);
//    System.out.println("==============================  RequestAdvice end==============================  ");
    return agentScope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATE.onError(scope.span(), throwable);
    DECORATE.beforeFinish(scope.span());

    scope.close();
    scope.span().finish();
    CallDepthThreadLocalMap.reset(RpcContext.class);
  }
}
