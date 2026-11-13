package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;
import java.util.ArrayList;

/** Keeps MBean servers created by build-time initializers out of the image heap. */
@TargetClass(className = "javax.management.MBeanServerFactory")
public final class Target_javax_management_MBeanServerFactory {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.NewInstance, declClass = ArrayList.class)
  private static ArrayList<?> mBeanServerList;
}
