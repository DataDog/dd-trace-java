package datadog.smoketest.fieldinjection;

import java.lang.reflect.Field;

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
          klass = klass.getSuperclass();
        }
      } catch (ClassNotFoundException e) {
        e.printStackTrace(System.err);
      }
    }
  }
}
