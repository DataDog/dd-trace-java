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
    for (Map.Entry<String, List<String>> entry : target.entrySet()) {
      final String name = entry.getKey();
      module.taintString(ctx, name, nameType, name);
      for (String value : entry.getValue()) {
        module.taintString(ctx, value, type, name);
      }
    }
  }
}
