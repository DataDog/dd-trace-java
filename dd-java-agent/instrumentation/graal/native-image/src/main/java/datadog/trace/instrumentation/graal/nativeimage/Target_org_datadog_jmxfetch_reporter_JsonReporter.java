package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "org.datadog.jmxfetch.reporter.JsonReporter")
public final class Target_org_datadog_jmxfetch_reporter_JsonReporter {
  @Substitute
  public void doSendServiceCheck(
      String serviceCheckName, String status, String message, String[] tags) {
    // This method has a reference to the excluded transitive dependency jackson-jr-objects.
    // GraalVM Native detects it during the reachability analysis and results in
    // "Discovered unresolved type during parsing: com.fasterxml.jackson.jr.ob.JSON."
    // because of the missing classes that belong to the excluded dependencies.
    throw new IllegalStateException("Unreachable");
  }
}
