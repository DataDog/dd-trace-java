package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as having exactly one instance for the life of the process -- constructed once,
 * reachable only through a single static field, DI-container registration, or other holder that
 * itself guarantees there is no second instance.
 *
 * <p>This exists as the escape valve for {@link StaticLifetime}: a field can legitimately live on
 * an instance, not as {@code static}, if that instance itself is guaranteed to be a process-wide
 * singleton. Without this annotation, {@code @StaticLifetime} would have no way to accept a
 * legitimate cache held on a singleton-scoped instance without also accepting one held on an
 * ordinary, possibly-repeatedly-constructed instance -- which is exactly the defect shape it exists
 * to catch.
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior.
 *
 * <p><b>v1 posture: trusted declaration, not independently verified.</b> This is the same stance
 * the other perf-contract annotations take toward {@code static final} itself -- declared, not
 * proven. Verifying it for real (a single construction site, or an instance reachable only via one
 * static/DI-registered path) is a call-site/construction-graph problem, out of scope for v1.
 * Annotating a class that is, in fact, constructed more than once defeats every guarantee
 * downstream checks (starting with {@link StaticLifetime}) build on top of this annotation -- apply
 * it with the same care as any other unverified perf-contract claim.
 *
 * <p><b>On a class</b> ({@link ElementType#TYPE}): every instance field of this class may serve as
 * the process-wide holder {@link StaticLifetime} requires, because the class itself is guaranteed
 * to have at most one instance.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Singleton {}
