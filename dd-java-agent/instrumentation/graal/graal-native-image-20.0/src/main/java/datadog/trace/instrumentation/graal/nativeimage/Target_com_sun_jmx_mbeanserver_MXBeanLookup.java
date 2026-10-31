package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;

/** Starts the native executable with an empty cache of per-server MXBean lookups. */
@TargetClass(className = "com.sun.jmx.mbeanserver.MXBeanLookup")
public final class Target_com_sun_jmx_mbeanserver_MXBeanLookup {
  @Alias
  @RecomputeFieldValue(
      kind = RecomputeFieldValue.Kind.NewInstance,
      declClassName = "com.sun.jmx.mbeanserver.WeakIdentityHashMap",
      isFinal = true)
  private static Target_com_sun_jmx_mbeanserver_WeakIdentityHashMap mbscToLookup;
}
