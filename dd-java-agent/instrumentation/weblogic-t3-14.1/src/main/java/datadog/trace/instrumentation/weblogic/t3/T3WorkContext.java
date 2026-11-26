package datadog.trace.instrumentation.weblogic.t3;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.currentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;

import datadog.context.Context;
import datadog.context.ContextScope;
import java.util.LinkedHashMap;
import java.util.Map;
import weblogic.workarea.PrimitiveContextFactory;
import weblogic.workarea.PropagationMode;
import weblogic.workarea.StringWorkContext;
import weblogic.workarea.WorkContext;
import weblogic.workarea.WorkContextHelper;
import weblogic.workarea.WorkContextMap;

public final class T3WorkContext {
  public static final String KEY = "datadog.trace.t3.v1";

  private T3WorkContext() {}

  public static WorkContextMap inject() throws Exception {
    Context context = currentContext();
    if (context == rootContext()) {
      return null;
    }
    Map<String, String> fields = new LinkedHashMap<>();
    defaultPropagator().inject(context, fields, Map::put);
    String encoded = T3PropagationFields.encode(fields);
    if (encoded == null) {
      return null;
    }
    WorkContextMap map = WorkContextHelper.getWorkContextHelper().getWorkContextMap();
    if (map.get(KEY) != null) {
      return null;
    }
    map.put(
        KEY, PrimitiveContextFactory.create(encoded), PropagationMode.RMI | PropagationMode.ONEWAY);
    return map;
  }

  public static ContextScope extract() throws Exception {
    WorkContextMap map = WorkContextHelper.getWorkContextHelper().getWorkContextMap();
    WorkContext item = map.get(KEY);
    if (!(item instanceof StringWorkContext)) {
      return null;
    }
    // A nested call must inject its current parent instead of forwarding the received entry.
    map.remove(KEY);
    Map<String, String> fields =
        T3PropagationFields.decode((String) ((StringWorkContext) item).get());
    if (fields.isEmpty()) {
      return null;
    }
    Context root = rootContext();
    Context context = defaultPropagator().extract(root, fields, Map::forEach);
    return context == root ? null : context.attach();
  }
}
