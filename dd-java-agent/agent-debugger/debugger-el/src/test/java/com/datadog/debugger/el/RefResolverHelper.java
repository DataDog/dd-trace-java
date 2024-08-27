package com.datadog.debugger.el;

import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.util.*;

public class RefResolverHelper {

  public static ValueReferenceResolver createResolver(Object instance) {
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
