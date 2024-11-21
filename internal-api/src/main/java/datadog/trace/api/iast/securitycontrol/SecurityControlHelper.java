package datadog.trace.api.iast.securitycontrol;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;

public class SecurityControlHelper {

  public static void setSecureMarks(final Object target, int marks) {

    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    try {
      if (module != null) {
        module.markIfTainted(target, marks);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterRepeat threw", e);
    }
  }
}
