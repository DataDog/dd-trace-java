package datadog.trace.instrumentation.springsecurity5;

import net.bytebuddy.asm.Advice;

public class UsernameNotFoundExceptionAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter() {
    SpringSecurityUserEventDecorator.DECORATE.onUserNotFound();
  }
}
