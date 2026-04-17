package datadog.trace.junit.utils.config;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Container annotation for repeatable {@link WithConfig}. */
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@ExtendWith(WithConfigExtension.class)
public @interface WithConfigs {
  WithConfig[] value();
}
