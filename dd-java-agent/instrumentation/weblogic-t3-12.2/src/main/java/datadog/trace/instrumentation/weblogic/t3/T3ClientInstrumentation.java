package datadog.trace.instrumentation.weblogic.t3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class T3ClientInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public T3ClientInstrumentation() {
    super("weblogic-t3");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "weblogic.rjvm.BasicOutboundRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("transferThreadLocalContext").and(takesArguments(0)).and(returns(void.class)),
        getClass().getName() + "$TransferAdvice");
  }

  public static class TransferAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AutoCloseable onEnter() throws Exception {
      return T3WorkContext.inject();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AutoCloseable workArea) throws Exception {
      if (workArea != null) {
        workArea.close();
      }
    }
  }
}
