package datadog.trace.instrumentation.springsecurity6;


import datadog.trace.api.Config;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.bootstrap.instrumentation.decorator.UserEventDecorator;
import net.bytebuddy.asm.Advice;
import org.springframework.security.core.userdetails.UserDetails;

import static datadog.trace.api.UserEventTrackingMode.EXTENDED;
import static datadog.trace.api.UserEventTrackingMode.SAFE;

public class SignupAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Argument(value = 0, readOnly = false) UserDetails user) {
    System.out.println("Signup Advice");
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode == SAFE) {
      UserEventDecorator.DECORATE.onSignup(null, null);
    } else if (mode == EXTENDED) {
      // TODO: Implement advanced logic to collect and report sensitive data
    }
  }
}
