package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class BoltServerProcessorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.alipay.sofa.rpc.server.bolt.BoltServerProcessor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArguments(3))
            .and(takesArgument(2, named("com.alipay.sofa.rpc.core.request.SofaRequest"))),
        getClass().getName() + "$HandleRequestAdvice");
  }

  public static class HandleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      SofaRpcProtocolContext.set("bolt");
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit() {
      SofaRpcProtocolContext.clear();
    }
  }
}
