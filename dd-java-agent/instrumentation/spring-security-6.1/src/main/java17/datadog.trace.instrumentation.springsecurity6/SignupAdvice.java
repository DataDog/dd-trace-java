package datadog.trace.instrumentation.springsecurity6;

import net.bytebuddy.asm.Advice;
import org.springframework.security.core.userdetails.UserDetails;

public class SignupAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Argument(value = 0, readOnly = false) UserDetails user) {
    System.out.format("Signup success [%s]\r\n", user.getUsername());
  }
}
