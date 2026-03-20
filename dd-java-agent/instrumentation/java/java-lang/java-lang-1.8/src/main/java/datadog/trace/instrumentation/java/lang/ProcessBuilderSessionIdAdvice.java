package datadog.trace.instrumentation.java.lang;

import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;

class ProcessBuilderSessionIdAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeStart(@Advice.This final ProcessBuilder self) {
    Config config = Config.get();
    String rootSessionId = config.getRootSessionId();
    if (rootSessionId != null) {
      self.environment().put("_DD_ROOT_JAVA_SESSION_ID", rootSessionId);
    }
  }
}
