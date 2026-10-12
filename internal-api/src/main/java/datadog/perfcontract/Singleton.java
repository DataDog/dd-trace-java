package datadog.perfcontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a class has one instance for the life of the process. All construction paths must
 * preserve that guarantee; a static holder or DI registration alone does not prevent another
 * instance from being created.
 *
 * <p>This marker changes no runtime behavior. Tools trust the declaration without checking
 * construction sites. If the class is instantiated more than once, they may accept caches that are
 * rebuilt with each instance.
 *
 * <p><b>Checker contract.</b> This annotation has no violation rule of its own. It allows {@link
 * StaticLifetime}'s checker to accept {@code final} instance fields declared on the annotated
 * class.
 */
@Documented
@PerfContract
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Singleton {}
