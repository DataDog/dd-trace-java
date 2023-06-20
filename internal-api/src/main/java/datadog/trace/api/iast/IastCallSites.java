package datadog.trace.api.iast;

import datadog.trace.api.iast.telemetry.Verbosity;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interface used to mark advice implementations using @CallSite so they are instrumented by
 * IastInstrumentation
 */
public interface IastCallSites {

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.CLASS)
  @interface Propagation {}

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.CLASS)
  @interface Sink {
    /** Vulnerability type */
    String value();
  }

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.CLASS)
  @interface Source {
    /** Source type */
    byte value();
  }

  interface HasTelemetry {
    void setVerbosity(Verbosity verbosity);
  }
}
