package datadog.trace.instrumentation.springsecurity5;

import net.bytebuddy.asm.Advice;
import org.springframework.security.core.AuthenticationException;

public class AbstractUserDetailsAuthenticationProviderAdvice {

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
      @Advice.Enter final boolean originalValue,
      @Advice.FieldValue(value = "hideUserNotFoundExceptions", readOnly = false)
          boolean hideUserNotFoundExceptions) {
    // Restore original value
    hideUserNotFoundExceptions = originalValue;
  }
}
