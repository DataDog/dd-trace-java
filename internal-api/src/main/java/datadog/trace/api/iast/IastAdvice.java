package datadog.trace.api.iast;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interface used to mark advice implementations using @CallSite so they are instrumented by
 * IastInstrumentation
 */
public interface IastAdvice {

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
    Kind kind();

    void enableTelemetry(boolean runtime);
  }
}
