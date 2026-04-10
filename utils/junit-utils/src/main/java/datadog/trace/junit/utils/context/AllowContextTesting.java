package datadog.trace.junit.utils.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Enables context testing by allowing re-registration of {@code ContextManager} and {@code
 * ContextBinder} singletons.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(AllowContextTestingExtension.class)
public @interface AllowContextTesting {}
