package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "datadog.jctools.counters.FixedSizeStripedLongCounterFields")
public final class Target_datadog_jctools_counters_FixedSizeStripedLongCounterFields {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = long[].class)
  public static long COUNTER_ARRAY_BASE;
}
