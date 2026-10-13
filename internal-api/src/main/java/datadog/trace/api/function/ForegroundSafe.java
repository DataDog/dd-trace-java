package datadog.trace.api.function;

import datadog.perfcontract.PerfContract;
import datadog.perfcontract.SuppressPerfContract;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code cheap enough to call from an application thread (the foreground) -- the request or
 * transaction thread the instrumented application itself is running, where any added cost is
 * customer-visible latency, as opposed to a background thread the tracer owns and paces itself (see
 * {@link BackgroundOnly}).
 *
 * <p>This marker changes no runtime behavior. It telegraphs the guarantee to readers and marks
 * where foreground code starts for the rule in {@link BackgroundOnly}'s checker contract: no
 * {@code @BackgroundOnly} code may be called from a foreground call site. AI review applies that
 * rule through the perf-review skill's {@code checks.md}; no static checker enforces it yet (see
 * {@code APMLP-1645}). A deliberate exception is marked with {@link SuppressPerfContract} citing
 * {@link BackgroundOnly}.
 *
 * <p>The two markers are <b>not symmetric</b>. {@code @ForegroundSafe} is the strictly stronger
 * guarantee: code cheap enough for the foreground is automatically fine to call from a background
 * thread too, so a {@code @ForegroundSafe} type or method may be called from either. {@link
 * BackgroundOnly} code carries no such guarantee and must never be reached from a foreground call
 * site.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): every method of this type is foreground-safe
 * unless a method-level {@link BackgroundOnly} narrows it.
 *
 * <p><b>On a method</b> ({@link ElementType#METHOD}): this method specifically is foreground-safe,
 * regardless of what the enclosing type declares -- a method-level marker always wins over the
 * type-level one.
 */
@Documented
@PerfContract
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ForegroundSafe {}
