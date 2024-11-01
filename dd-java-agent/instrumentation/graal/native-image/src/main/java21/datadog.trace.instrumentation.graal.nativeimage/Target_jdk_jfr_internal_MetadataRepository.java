package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jfr.JfrHostedEnabled;
import jdk.jfr.internal.MetadataRepository;

@TargetClass(value = MetadataRepository.class, onlyWith = JfrHostedEnabled.class)
final class Target_jdk_jfr_internal_MetadataRepository {
  /*
   * Ignore all state of the FlightRecorder maintained when profiling the image generator itself.
   */

  @Alias //
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.FromAlias) //
  private static MetadataRepository instance = new MetadataRepository();
}
