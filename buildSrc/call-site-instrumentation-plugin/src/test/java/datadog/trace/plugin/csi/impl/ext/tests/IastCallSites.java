package datadog.trace.plugin.csi.impl.ext.tests;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface IastCallSites {

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @interface Propagation {
    Kind kind() default Kind.PROPAGATION;
  }

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @interface Sink {
    /** Vulnerability type */
    String value();

    Kind kind() default Kind.SINK;
  }

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @interface Source {
    /** Source type */
    byte value();

    Kind kind() default Kind.SOURCE;
  }

  enum Kind {
    PROPAGATION,
    SINK,
    SOURCE
  }

  interface HasTelemetry {
    void setVerbosity(Object verbosity);
  }
}
