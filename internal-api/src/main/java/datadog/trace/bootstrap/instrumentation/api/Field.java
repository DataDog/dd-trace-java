package datadog.trace.bootstrap.instrumentation.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the field a getter of a field accessor interface returns, when it differs from the getter's
 * name without its {@code $$DD$$__} prefix: {@code @Field("buffer") ByteBuffer $$DD$$__keyBuffer()}
 * reads {@code buffer}. See {@code datadog.trace.agent.tooling.bytebuddy.FieldAccessors}.
 *
 * <p>It lives on the bootstrap class path so it's readable whether the accessor interface is there
 * too or in an instrumentation module: an annotation whose class the interface's loader can't see
 * is silently dropped.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Field {
  /** The name of the field the getter returns. */
  String value();
}
