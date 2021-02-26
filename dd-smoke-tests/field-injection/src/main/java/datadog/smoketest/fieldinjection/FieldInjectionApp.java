package datadog.smoketest.fieldinjection;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

@SuppressForbidden
public class FieldInjectionApp {

  public static void main(String... args) {
    for (String className : args) {
      try {
        Class<?> klass = Class.forName(className);
        while (klass != null) {
          for (Field field : klass.getDeclaredFields()) {
            if (field.getName().startsWith("__datadogContext")) {
              System.err.println("___FIELD___:" + className + ":" + field.getName());
            }
          }
          for (Class<?> intf : klass.getInterfaces()) {
            System.err.println("___INTERFACE___:" + className + ":" + intf.getName());
          }
          for (Type genericIntf : klass.getGenericInterfaces()) {
            Class<?> intf;
            if (genericIntf instanceof ParameterizedType) {
              intf = (Class<?>) ((ParameterizedType) genericIntf).getRawType();
            } else {
              intf = (Class<?>) genericIntf;
            }
            System.err.println("___GENERIC_INTERFACE___:" + className + ":" + intf.getName());
          }
          klass = klass.getSuperclass();
        }
      } catch (ClassNotFoundException e) {
        e.printStackTrace(System.err);
      }
    }
  }
}
