package datadog.trace.instrumentation.springsecurity6;

import net.bytebuddy.asm.Advice;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public class LoginAdvice {

  @Advice.OnMethodEnter
  public static boolean onEnter(
      @Advice.FieldValue(value = "hideUserNotFoundExceptions", readOnly = false)
          boolean hideUserNotFoundExceptions) {
    // Ensure we are not hiding exception
    boolean originalValue = hideUserNotFoundExceptions;
    hideUserNotFoundExceptions = false;
    return originalValue;
  }

  @Advice.OnMethodExit(onThrowable = AuthenticationException.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(value = 0, readOnly = false) Authentication authentication,
      @Advice.Enter final boolean originalValue,
      @Advice.Return final Authentication result,
      @Advice.Thrown final AuthenticationException throwable,
      @Advice.FieldValue(value = "hideUserNotFoundExceptions", readOnly = false)
          boolean hideUserNotFoundExceptions) {

    // Restore original value
    hideUserNotFoundExceptions = originalValue;

    if (throwable == null && result.isAuthenticated()) {
      System.out.format("Login success [%s]\r\n", result.getName());
    } else {
      if (throwable instanceof UsernameNotFoundException) {
        System.out.format("Login failed user not found [%s]\r\n", authentication.getName());
      } else if (throwable instanceof BadCredentialsException) {
        System.out.format("Login failed user exist [%s]\r\n", authentication.getName());
      } else {
        System.out.println("Login failed - unknown reason");
      }
    }
  }
}
