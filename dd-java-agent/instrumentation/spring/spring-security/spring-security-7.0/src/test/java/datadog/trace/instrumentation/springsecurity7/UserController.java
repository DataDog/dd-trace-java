package datadog.trace.instrumentation.springsecurity7;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.instrumentation.springsecurity7.TestEndpoint.NOT_FOUND;
import static datadog.trace.instrumentation.springsecurity7.TestEndpoint.REGISTER;
import static datadog.trace.instrumentation.springsecurity7.TestEndpoint.SDK;
import static datadog.trace.instrumentation.springsecurity7.TestEndpoint.SUCCESS;
import static datadog.trace.instrumentation.springsecurity7.TestEndpoint.UNKNOWN;
import static java.util.Collections.emptyMap;

import datadog.trace.api.GlobalTracer;
import java.util.concurrent.Callable;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class UserController {

  private final UserDetailsManager userDetailsManager;

  public UserController(UserDetailsManager userDetailsManager) {
    this.userDetailsManager = userDetailsManager;
  }

  @RequestMapping("/success")
  @ResponseBody
  public String success() {
    return controller(
        SUCCESS,
        () -> {
          return SUCCESS.getBody();
        });
  }

  @PostMapping("/register")
  @ResponseBody
  public void register(
      @RequestParam("username") String username,
      @RequestParam("password") String password,
      Model model) {
    controller(
        REGISTER,
        () -> {
          userDetailsManager.createUser(
              User.withUsername(username).password("{noop}" + password).roles("USER").build());
          model.addAttribute("username", username);
          return null;
        });
  }

  @PostMapping("/sdk")
  @ResponseBody
  public String sdk(
      @RequestParam(name = "sdkEvent", defaultValue = "login.success") String event,
      @RequestParam(name = "sdkUser", required = false) String sdkUser) {
    return controller(
        SDK,
        () -> {
          switch (event) {
            case "login.success":
              GlobalTracer.getEventTracker().trackLoginSuccessEvent(sdkUser, emptyMap());
              break;
            case "login.failure":
              GlobalTracer.getEventTracker().trackLoginFailureEvent(sdkUser, false, emptyMap());
              break;
            case "setUser":
              datadog.appsec.api.user.User.setUser(sdkUser, emptyMap());
              break;
            default:
              GlobalTracer.getEventTracker().trackCustomEvent(event, emptyMap());
              break;
          }
          return "OK";
        });
  }

  static <T> T controller(TestEndpoint endpoint, Callable<T> callable) {
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      try {
        return callable.call();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return runUnderTrace("controller", callable);
  }
}
