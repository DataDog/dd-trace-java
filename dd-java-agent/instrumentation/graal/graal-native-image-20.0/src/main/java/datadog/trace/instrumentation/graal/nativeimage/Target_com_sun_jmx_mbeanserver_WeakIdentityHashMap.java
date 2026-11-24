package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.TargetClass;

/** Type alias for the JDK-internal map used by MXBeanLookup. */
@TargetClass(className = "com.sun.jmx.mbeanserver.WeakIdentityHashMap")
public final class Target_com_sun_jmx_mbeanserver_WeakIdentityHashMap {}
