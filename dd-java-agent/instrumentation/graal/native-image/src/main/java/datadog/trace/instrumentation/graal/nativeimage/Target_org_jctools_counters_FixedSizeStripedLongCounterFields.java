package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "org.jctools.counters.FixedSizeStripedLongCounterFields")
public final class Target_org_jctools_counters_FixedSizeStripedLongCounterFields {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = long[].class)
  public static long COUNTER_ARRAY_BASE;
}
