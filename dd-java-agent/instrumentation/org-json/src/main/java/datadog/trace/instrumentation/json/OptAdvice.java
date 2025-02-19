package datadog.trace.instrumentation.json;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import org.json.JSONArray;
import org.json.JSONObject;

public class OptAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Propagation
  public static void afterMethod(@Advice.This Object self, @Advice.Return final Object result) {
    boolean isString = result instanceof String;
    boolean isJson = !isString && (result instanceof JSONObject || result instanceof JSONArray);
    if (!isString && !isJson) {
      return;
    }
    final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
    if (iastModule != null) {
      if (isString) {
        iastModule.taintStringIfTainted((String) result, self);
      } else {
        iastModule.taintObjectIfTainted(result, self);
      }
    }
  }
}
