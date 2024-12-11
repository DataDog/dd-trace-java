package datadog.test.agent.junit5;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({ FIELD, ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface FromTestAgent {
}
