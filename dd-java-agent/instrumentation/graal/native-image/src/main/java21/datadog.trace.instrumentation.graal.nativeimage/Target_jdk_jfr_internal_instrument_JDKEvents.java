package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.JDK21OrEarlier;
import com.oracle.svm.core.jfr.HasJfrSupport;
import jdk.jfr.events.ActiveRecordingEvent;
import jdk.jfr.events.ActiveSettingEvent;
import jdk.jfr.events.SocketReadEvent;
import jdk.jfr.events.SocketWriteEvent;

@TargetClass(
    className = "jdk.jfr.internal.instrument.JDKEvents",
    onlyWith = {JDK21OrEarlier.class, HasJfrSupport.class})
final class Target_jdk_jfr_internal_instrument_JDKEvents {

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.FromAlias, isFinal = true)
  private static Class<?>[] eventClasses = {
    SocketReadEvent.class,
    SocketWriteEvent.class,
    ActiveSettingEvent.class,
    ActiveRecordingEvent.class
  };

  // This is a list of the classes with instrumentation code that should be applied.
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.FromAlias, isFinal = true)
  private static Class<?>[] instrumentationClasses = new Class<?>[] {};

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.FromAlias, isFinal = true)
  private static Class<?>[] mirrorEventClasses = new Class<?>[] {};
}
