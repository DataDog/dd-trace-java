package datadog.trace.api.function;

import datadog.perfcontract.PerfContract;
import datadog.perfcontract.SuppressPerfContract;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that must be confined to a background thread the tracer owns and paces itself -- e.g.
 * serialization, stats aggregation, or eviction -- and must never be reached from an application
 * thread (the foreground; see {@link ForegroundSafe}), where its cost would become customer-visible
 * latency instead.
 *
 * <p>This marker changes no runtime behavior. The checker contract below guides AI review through
 * the perf-review skill's {@code checks.md}; no static checker enforces it yet (see {@code
 * APMLP-1645}).
 *
 * <p>The two markers are <b>not symmetric</b> -- see {@link ForegroundSafe} for why it, not this
 * one, is the strictly stronger guarantee.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): every method of this type is background-only
 * unless a method-level {@link ForegroundSafe} widens it.
 *
 * <p><b>On a method</b> ({@link ElementType#METHOD}): this method specifically is background-only,
 * regardless of what the enclosing type declares -- a method-level marker always wins over the
 * type-level one.
 *
 * <p><b>Checker contract.</b> A trigger is a presumptive finding to raise, not an automatic
 * failure.
 *
 * <ul>
 *   <li><b>Foreground code:</b> a method annotated {@link ForegroundSafe}; a method of a type
 *       annotated {@code @ForegroundSafe} that has no method-level {@code @BackgroundOnly}; or an
 *       advice method annotated {@code @Advice.OnMethodEnter} or {@code @Advice.OnMethodExit}.
 *       ByteBuddy inlines advice into the instrumented application method, so it runs on the
 *       application thread by construction.
 *   <li><b>Background-only code:</b> a method annotated {@code @BackgroundOnly}, or a method of a
 *       type annotated {@code @BackgroundOnly} that has no method-level {@code @ForegroundSafe}.
 *   <li><b>Trigger:</b> foreground code that directly calls background-only code.
 *   <li><b>Not a trigger:</b> background-only code calling foreground code; foreground code that
 *       hands work to a background thread (for example by enqueueing it) without running it; or a
 *       calling method annotated {@link SuppressPerfContract} with {@code BackgroundOnly.class} in
 *       its {@code value} and a non-blank reason.
 *   <li><b>Violation example:</b> a {@code @ForegroundSafe} {@code onSpanFinished(span)} that calls
 *       a {@code @BackgroundOnly} {@code aggregate(span)}.
 *   <li><b>Compliant example:</b> the same {@code onSpanFinished(span)} that only enqueues {@code
 *       span}, so the background thread that drains the queue calls {@code aggregate}.
 *   <li><b>Out of scope (v1):</b> calls that reach background-only code through intermediate
 *       methods carrying neither marker. Flag direct calls only; widen this contract once a real
 *       case shows that direct calls are not enough.
 * </ul>
 */
@Documented
@PerfContract
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BackgroundOnly {}
