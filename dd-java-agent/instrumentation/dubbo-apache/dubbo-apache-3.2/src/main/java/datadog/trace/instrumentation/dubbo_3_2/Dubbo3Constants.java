package datadog.trace.instrumentation.dubbo_3_2;

import java.lang.reflect.Field;

public class Dubbo3Constants {
  public static final Object getValue(Class klass, Object instance, String name) throws NoSuchFieldException, IllegalAccessException {
    Field field = klass.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }
}
