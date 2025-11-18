package datadog.trace.instrumentation.jersey;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import java.util.Map;

public abstract class JerseyTaintHelper {

  private JerseyTaintHelper() {}

  public static void taintMultiValuedMap(
      final IastContext ctx,
      final PropagationModule module,
      final byte type,
      final Map<String, List<String>> target) {
    final byte nameType = SourceTypes.namedSource(type);
    final boolean reportName = nameType != type;
    for (Map.Entry<String, List<String>> entry : target.entrySet()) {
      final String name = entry.getKey();
      if (reportName) {
        module.taintString(ctx, name, nameType, name);
      }
      for (String value : entry.getValue()) {
        module.taintString(ctx, value, type, name);
      }
    }
  }

  public static void taintMap(
      final IastContext ctx,
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
          module.taintString(ctx, name, nameType, name);
        }
        final Object value = entry.getValue();
        if (value instanceof String) {
          module.taintString(ctx, (String) value, type, name);
        } else if (value instanceof List) {
          for (final Object item : (List<?>) value) {
            if (item instanceof String) {
              module.taintString(ctx, (String) item, type, name);
            }
          }
        }
      }
    }
  }
}
