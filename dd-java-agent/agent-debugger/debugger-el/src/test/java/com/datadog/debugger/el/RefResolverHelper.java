package com.datadog.debugger.el;

import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.util.*;

public class RefResolverHelper {

  public static ValueReferenceResolver createResolver(Object instance) {
    /*
    List<Field> fields = new ArrayList<>();
    Class<?> clazz = instance.getClass();
    while (clazz != null) {
      fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
      clazz = clazz.getSuperclass();
    }
    CapturedContext.CapturedValue[] fieldValues = new CapturedContext.CapturedValue[fields.size()];
    int index = 0;
    for (Field field : fields) {
      try {
        field.setAccessible(true);
        if (Redaction.isRedactedKeyword(field.getName())) {
          fieldValues[index++] =
              CapturedContext.CapturedValue.redacted(
                  field.getName(), field.getType().getTypeName());
        } else {
          fieldValues[index++] =
              CapturedContext.CapturedValue.of(
                  field.getName(), field.getType().getTypeName(), field.get(instance));
        }
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }

     */
    CapturedContext.CapturedValue thisValue =
        CapturedContext.CapturedValue.of("this", instance.getClass().getTypeName(), instance);
    return new CapturedContext(new CapturedContext.CapturedValue[] {thisValue}, null, null, null);
  }

  public static ValueReferenceResolver createResolver(
      Map<String, Object> args, Map<String, Object> locals) {
    CapturedContext.CapturedValue[] argValues = null;
    if (args != null) {
      argValues = new CapturedContext.CapturedValue[args.size()];
      fillValues(args, argValues);
    }
    CapturedContext.CapturedValue[] localValues = null;
    if (locals != null) {
      localValues = new CapturedContext.CapturedValue[locals.size()];
      fillValues(locals, localValues);
    }
    return new CapturedContext(argValues, localValues, null, null);
  }

  private static void fillValues(
      Map<String, Object> fields, CapturedContext.CapturedValue[] fieldValues) {
    int index = 0;
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      Object value = entry.getValue();
      if (Redaction.isRedactedKeyword(entry.getKey())) {
        fieldValues[index++] =
            CapturedContext.CapturedValue.redacted(
                entry.getKey(),
                value != null ? value.getClass().getTypeName() : Object.class.getTypeName());
      } else {
        fieldValues[index++] =
            CapturedContext.CapturedValue.of(
                entry.getKey(),
                value != null ? value.getClass().getTypeName() : Object.class.getTypeName(),
                value);
      }
    }
  }
}
