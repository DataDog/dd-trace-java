package datadog.smoketest.springboot.controller;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Method;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SuppressForbidden // Class.forName is needed to test Reflection Injection
@RestController
public class ReflectionController {

  @GetMapping("/reflection_injection/class")
  public String reflectionInjectionClass(final HttpServletRequest request) {
    String className = request.getParameter("param");
    try {
      Class<?> clazz = Class.forName(className);
      return "Class: " + clazz.getName();
    } catch (ClassNotFoundException e) {
      return "ClassNotFoundException";
    }
  }

  @GetMapping("/reflection_injection/method")
  public String reflectionInjectionMethod(final HttpServletRequest request) {
    String methodName = request.getParameter("param");
    try {
      Method method = String.class.getMethod(methodName);
      return "String Method: " + method.getName();
    } catch (NoSuchMethodException e) {
      return "NoSuchMethodException";
    }
  }
}
