package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "org.datadog.jmxfetch.App")
public final class Target_org_datadog_jmxfetch_App {
  @Substitute
  private boolean getJsonConfigs() {
    // Remove getJsonConfigs body to fix the GraalVM native build error.
    //
    // This method is not needed in Java Tracer, so we exclude next transitive dependencies when embedding JMXFetch:
    // - jackson-core (catch JsonProcessingException)
    // - jackson-jr-objects (referenced in org.datadog.jmxfetch.JsonParser).
    // But GraalVM Native detects it during the reachability analysis and results in
    // "Discovered unresolved method during parsing: org.datadog.jmxfetch.App.<init>(org.datadog.jmxfetch.AppConfig)."
    // because of the missing classes that belong to the excluded dependencies.
    return false;
  }
}
