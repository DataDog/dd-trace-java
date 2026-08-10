package com.datadog.debugger.el;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.util.*;

public class EvalContextHelper {

  public static final Duration TEST_TIMEOUT = Duration.ofMillis(1000);

  public static EvalContext createEvalContext(Object instance) {
    // create a higher timeout for test to avoid flakiness
    return new EvalContext(
        createResolver(instance), TimeoutChecker.create(Config.get(), TEST_TIMEOUT));
  }

  // specify lower timeout to test timeout checker
  public static EvalContext createEvalContext(Object instance, Duration timeout) {
    return new EvalContext(createResolver(instance), TimeoutChecker.create(Config.get(), timeout));
  }

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
