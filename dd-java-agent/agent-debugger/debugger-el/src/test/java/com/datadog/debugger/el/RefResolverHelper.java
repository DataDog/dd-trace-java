package com.datadog.debugger.el;

import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.lang.reflect.Field;
import java.util.*;

public class RefResolverHelper {

  public static ValueReferenceResolver createResolver(Object instance) {
    List<Field> fields = new ArrayList<>();
    Class<?> clazz = instance.getClass();
    while (clazz != null) {
      fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
      clazz = clazz.getSuperclass();
    }
    Snapshot.CapturedValue[] fieldValues = new Snapshot.CapturedValue[fields.size()];
    int index = 0;
    for (Field field : fields) {
      try {
        field.setAccessible(true);
        fieldValues[index++] =
            Snapshot.CapturedValue.of(
                field.getName(), field.getType().getTypeName(), field.get(instance));
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
    return new Snapshot.CapturedContext(null, null, null, null, fieldValues);
  }

  public static ValueReferenceResolver createResolver(
      Map<String, Object> locals, Map<String, Object> fields) {
    Snapshot.CapturedValue[] localValues = null;
    if (locals != null) {
      localValues = new Snapshot.CapturedValue[locals.size()];
      fillValues(locals, localValues);
    }
    Snapshot.CapturedValue[] fieldValues = null;
    if (fields != null) {
      fieldValues = new Snapshot.CapturedValue[fields.size()];
      fillValues(fields, fieldValues);
    }
    return new Snapshot.CapturedContext(null, localValues, null, null, fieldValues);
  }

  private static void fillValues(Map<String, Object> fields, Snapshot.CapturedValue[] fieldValues) {
    int index = 0;
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      fieldValues[index++] =
          Snapshot.CapturedValue.of(entry.getKey(), String.class.getTypeName(), entry.getValue());
    }
  }
}
