package datadog.trace.instrumentation.java.lang;

import datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers;
import java.io.IOException;
import net.bytebuddy.asm.Advice;

class RuntimeExecStringAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeExec(@Advice.Argument(0) final String command) throws IOException {
    if (command == null) {
      return;
    }
    ProcessImplInstrumentationHelpers.shiRaspCheck(command);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void afterExec() {
    ProcessImplInstrumentationHelpers.resetCheckShi();
  }
}
