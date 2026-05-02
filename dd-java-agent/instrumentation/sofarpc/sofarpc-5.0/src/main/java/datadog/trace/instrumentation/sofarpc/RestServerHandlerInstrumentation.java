package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class RestServerHandlerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.alipay.sofa.rpc.server.rest.SofaRestRequestHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("channelRead0")).and(takesArguments(2)),
        getClass().getName() + "$ChannelRead0Advice");
  }

  public static class ChannelRead0Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      SofaRpcProtocolContext.set("rest");
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit() {
      SofaRpcProtocolContext.clear();
    }
  }
}
