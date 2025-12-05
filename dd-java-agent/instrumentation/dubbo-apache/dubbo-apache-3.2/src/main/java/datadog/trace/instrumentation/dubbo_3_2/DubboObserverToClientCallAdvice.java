package datadog.trace.instrumentation.dubbo_3_2;


import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.dubbo.rpc.protocol.tri.call.ClientCall;
import org.apache.dubbo.rpc.protocol.tri.call.TripleClientCall;

import static datadog.trace.instrumentation.dubbo_3_2.DubboDecorator.DECORATE;

public class DubboObserverToClientCallAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.FieldValue("call") ClientCall call) {
    TripleClientCall tripleClientCall = (TripleClientCall) call ;
    return DECORATE.clientCall(tripleClientCall);
  }
//
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATE.beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }

}
