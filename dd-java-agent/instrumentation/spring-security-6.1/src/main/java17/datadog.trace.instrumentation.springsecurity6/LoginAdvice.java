package datadog.trace.instrumentation.springsecurity6;

import datadog.trace.api.Config;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.bootstrap.instrumentation.decorator.UserEventDecorator;
import net.bytebuddy.asm.Advice;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static datadog.trace.api.UserEventTrackingMode.EXTENDED;
import static datadog.trace.api.UserEventTrackingMode.SAFE;

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
    System.out.println("Login Advice");

    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode == SAFE) {
      if (throwable == null && result.isAuthenticated()) {
        UserEventDecorator.DECORATE.onLoginSuccess(null, null);
      } else if (throwable instanceof UsernameNotFoundException) {
        UserEventDecorator.DECORATE.onLoginFailure(null, null, false);
      } else if (throwable instanceof BadCredentialsException) {
        UserEventDecorator.DECORATE.onLoginFailure(null, null, true);
      }
    } else if (mode == EXTENDED) {
      // TODO: Implement advanced logic to collect and report sensitive data
    }
  }
}
