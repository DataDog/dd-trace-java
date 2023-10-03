package datadog.trace.agent.tooling.bytebuddy.iast;

import net.bytebuddy.asm.Advice;

public class MrReturnAdvice {
  @Advice.OnMethodExit
  public static void sayHello(@Advice.Return(readOnly = false) String result) {
    result = "Hello Mr!";
  }
}
