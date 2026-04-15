package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class H2cServerTaskInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.alipay.sofa.rpc.server.http.AbstractHttpServerTask";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("run")).and(takesNoArguments()),
        getClass().getName() + "$RunAdvice");
  }

  public static class RunAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      SofaRpcProtocolContext.set("h2c");
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit() {
      SofaRpcProtocolContext.clear();
    }
  }
}
