package datadog.trace.instrumentation.weblogic.t3;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.propagation.Propagators.defaultPropagator;
import static weblogic.workarea.PrimitiveContextFactory.create;
import static weblogic.workarea.PropagationMode.ONEWAY;
import static weblogic.workarea.PropagationMode.RMI;
import static weblogic.workarea.WorkContextHelper.getWorkContextHelper;

import datadog.context.Context;
import datadog.context.ContextScope;
import java.util.LinkedHashMap;
import java.util.Map;
import weblogic.workarea.StringWorkContext;
import weblogic.workarea.WorkContext;
import weblogic.workarea.WorkContextMap;

public final class T3WorkContext {
  public static final String KEY = "datadog.trace.t3.v1";

  private T3WorkContext() {}

  public static AutoCloseable inject() throws Exception {
    Context context = current();
    if (context == root()) {
      return null;
    }
    Map<String, String> fields = new LinkedHashMap<>();
    defaultPropagator().inject(context, fields, Map::put);
    String encoded = T3PropagationFields.encode(fields);
    if (encoded == null) {
      return null;
    }
    WorkContextMap map = getWorkContextHelper().getWorkContextMap();
    int previousMode = map.getPropagationMode(KEY);
    WorkContext previous = map.put(KEY, create(encoded), RMI | ONEWAY);
    return previous == null ? () -> map.remove(KEY) : () -> map.put(KEY, previous, previousMode);
  }

  public static ContextScope extract() throws Exception {
    WorkContextMap map = getWorkContextHelper().getWorkContextMap();
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
    Context root = root();
    Context context = defaultPropagator().extract(root, fields, Map::forEach);
    return context == root ? null : context.attach();
  }
}
