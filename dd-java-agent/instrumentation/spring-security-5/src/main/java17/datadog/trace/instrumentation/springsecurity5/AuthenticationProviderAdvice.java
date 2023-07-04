package datadog.trace.instrumentation.springsecurity5;

import net.bytebuddy.asm.Advice;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public class AuthenticationProviderAdvice {

  @Advice.OnMethodExit(onThrowable = AuthenticationException.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(value = 0, readOnly = false) Authentication authentication,
      @Advice.Return final Authentication result,
      @Advice.Thrown final AuthenticationException throwable) {
    SpringSecurityUserEventDecorator.DECORATE.onLogin(authentication, throwable, result);
  }
}
