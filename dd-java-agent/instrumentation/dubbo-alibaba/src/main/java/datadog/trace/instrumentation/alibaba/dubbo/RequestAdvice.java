package datadog.trace.instrumentation.alibaba.dubbo;

import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.instrumentation.alibaba.dubbo.DubboDecorator.DECORATE;

public class RequestAdvice {
  public static final Logger logger = LoggerFactory.getLogger(RequestAdvice.class);

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(@Advice.This Filter filter, @Advice.Argument(0) final Invoker invoker,
                                        @Advice.Argument(1) final Invocation invocation) {
    //logger.info("dubboFilterName:"+filter.getClass().getName());

//    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(RpcContext.class);
//    if (callDepth > 0) {
//      return null;
//    }
    AgentScope agentScope = DECORATE.buildSpan(invoker, invocation);
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
//    CallDepthThreadLocalMap.reset(RpcContext.class);
  }
}
