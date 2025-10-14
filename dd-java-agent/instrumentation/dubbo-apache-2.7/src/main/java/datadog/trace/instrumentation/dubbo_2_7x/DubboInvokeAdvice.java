package datadog.trace.instrumentation.dubbo_2_7x;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.dubbo_2_7x.DubboDecorator.DECORATE;
import static datadog.trace.instrumentation.dubbo_2_7x.DubboHeadersExtractAdapter.GETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import net.bytebuddy.asm.Advice;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;

public class DubboInvokeAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(
      @Advice.This Filter filter,
      @Advice.Argument(1) Invocation invocation,
      @Advice.Local("hasSpan") boolean hasSpan) {
    DubboTraceInfo dubboTraceInfo =
        new DubboTraceInfo((RpcInvocation) invocation, RpcContext.getContext());
    /*    AgentScope scope = activeScope();
    if(filter.getClass().getPackage().getName().contains("org.apache.dubbo")){
      return scope;
    }
    if (null == scope) {
      AgentSpanContext parentContext = extractContextAndGetSpanContext(dubboTraceInfo, GETTER);
      if (null != parentContext) {
        hasSpan = true;
        return activateSpan(startSpan("dubbo/filter",parentContext));
      }
    }
    return scope;*/

    AgentSpanContext parentContext = extractContextAndGetSpanContext(dubboTraceInfo, GETTER);
    if (null != parentContext) {
      hasSpan = true;
      return activateSpan(startSpan("dubbo/filter", parentContext));
    }

    AgentSpan span = startSpan("dubbo/filter");

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,
      @Advice.Local("hasSpan") boolean hasSpan,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    if (hasSpan) {
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());

      scope.close();
      scope.span().finish();
    }
  }
}
