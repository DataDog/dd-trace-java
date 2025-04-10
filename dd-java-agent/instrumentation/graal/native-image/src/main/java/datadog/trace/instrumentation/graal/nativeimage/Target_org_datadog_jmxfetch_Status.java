package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.io.IOException;

@TargetClass(className = "org.datadog.jmxfetch.Status")
public final class Target_org_datadog_jmxfetch_Status {
  @Substitute
  private String generateJson() throws IOException {
    // Replace org.datadog.jmxfetch.Status.generateJson to fix the GraalVM native build error.
    //
    // This method has a reference to the excluded transitive dependency jackson-jr-objects.
    // GraalVM Native detects it during the reachability analysis and results in
    // "Discovered unresolved type during parsing: com.fasterxml.jackson.jr.ob.JSON."
    // because of the missing classes that belong to the excluded dependencies.
    return "";
  }
}
