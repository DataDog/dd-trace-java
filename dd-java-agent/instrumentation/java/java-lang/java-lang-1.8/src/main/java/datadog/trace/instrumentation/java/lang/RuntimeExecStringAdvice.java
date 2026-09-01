package datadog.trace.instrumentation.java.lang;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers;
import net.bytebuddy.asm.Advice;

class RuntimeExecStringAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeExec(@Advice.Argument(0) final String command) {
    if (command == null || !AgentTracer.isRegistered()) {
      return;
    }
    ProcessImplInstrumentationHelpers.shiRaspCheck(command);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterExec() {
    ProcessImplInstrumentationHelpers.resetCheckShi();
  }
}
