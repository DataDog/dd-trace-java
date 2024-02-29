package datadog.smoketest.springboot.controller;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SuppressForbidden // Class.forName is needed to test Reflection Injection
@RestController
public class ReflectionController {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

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

  @GetMapping("/reflection_injection/field")
  public String reflectionInjectionField(final HttpServletRequest request) {
    String fieldName = request.getParameter("param");
    try {
      Field field = String.class.getField(fieldName);
      return "String Method: " + field.getName();
    } catch (NoSuchFieldException e) {
      return "NoSuchFieldException";
    }
  }

  @GetMapping("/reflection_injection/lookup")
  public String reflectionInjectionLookup(final HttpServletRequest request) {
    String param = request.getParameter("param");
    try {
      LOOKUP.findGetter(String.class, param, byte[].class);
      return "Lookup: " + param;
    } catch (Exception e) {
      return e.getMessage();
    }
  }
}
