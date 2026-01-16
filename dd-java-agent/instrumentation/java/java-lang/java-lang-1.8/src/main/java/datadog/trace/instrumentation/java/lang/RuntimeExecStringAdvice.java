package datadog.trace.instrumentation.java.lang;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers;
import net.bytebuddy.asm.Advice;

class RuntimeExecStringAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static boolean beforeExec(@Advice.Argument(0) final String command) {
    if (command == null || !AgentTracer.isRegistered()) {
      return false;
    }
    ProcessImplInstrumentationHelpers.shiRaspCheck(command);
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void afterExec(@Advice.Enter boolean checking) {
    if (checking) {
      ProcessImplInstrumentationHelpers.resetCheckShi();
    }
  }
}
