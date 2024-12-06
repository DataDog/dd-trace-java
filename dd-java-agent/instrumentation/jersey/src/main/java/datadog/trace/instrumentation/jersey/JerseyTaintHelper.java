package datadog.trace.instrumentation.jersey;

import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.List;
import java.util.Map;

public abstract class JerseyTaintHelper {

  private JerseyTaintHelper() {}

  public static void taintMultiValuedMap(
      final TaintedObjects to,
      final PropagationModule module,
      final byte type,
      final Map<String, List<String>> target) {
    final byte nameType = SourceTypes.namedSource(type);
    final boolean reportName = nameType != type;
    for (Map.Entry<String, List<String>> entry : target.entrySet()) {
      final String name = entry.getKey();
      if (reportName) {
        module.taintObject(to, name, nameType, name);
      }
      for (String value : entry.getValue()) {
        module.taintObject(to, value, type, name);
      }
    }
  }

  public static void taintMap(
      final TaintedObjects to,
      final PropagationModule module,
      final byte type,
      final Map<?, ?> target) {
    final byte nameType = SourceTypes.namedSource(type);
    final boolean reportName = nameType != type;
    for (final Map.Entry<?, ?> entry : target.entrySet()) {
      final Object key = entry.getKey();
      if (key instanceof String) {
        final String name = (String) key;
        if (reportName) {
          module.taintObject(to, name, nameType, name);
        }
        final Object value = entry.getValue();
        if (value instanceof String) {
          module.taintObject(to, value, type, name);
        } else if (value instanceof List) {
          for (final Object item : (List<?>) value) {
            if (item instanceof String) {
              module.taintObject(to, item, type, name);
            }
          }
        }
      }
    }
  }
}
