package test;

import net.bytebuddy.asm.Advice;

public class ConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit() {}
}
