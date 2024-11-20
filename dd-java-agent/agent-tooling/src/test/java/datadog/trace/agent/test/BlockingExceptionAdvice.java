package datadog.trace.agent.test;

import datadog.appsec.api.blocking.BlockingException;
import net.bytebuddy.asm.Advice;

public class BlockingExceptionAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void throwAnException() {
    throw new BlockingException("You are blocked");
  }
}
