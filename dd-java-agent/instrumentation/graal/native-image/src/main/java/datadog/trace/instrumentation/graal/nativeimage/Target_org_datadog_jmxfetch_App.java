package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "org.datadog.jmxfetch.App")
public final class Target_org_datadog_jmxfetch_App {
  @Substitute
  private boolean getJsonConfigs() {
    // This method has a reference to the excluded transitive dependency jackson-jr-objects.
    // GraalVM Native detects it during the reachability analysis and results in
    // "Discovered unresolved method during parsing:
    // org.datadog.jmxfetch.App.<init>(org.datadog.jmxfetch.AppConfig)."
    // because of the missing classes that belong to the excluded dependencies.
    throw new IllegalStateException("Unreachable");
  }
}
