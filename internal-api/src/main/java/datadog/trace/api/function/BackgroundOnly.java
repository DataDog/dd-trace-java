package datadog.trace.api.function;

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
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * constraint to readers and to give a future checker (see {@code APMLP-1645}) something to verify
 * -- that no {@code @BackgroundOnly} code is reachable from a foreground call site. The discipline
 * it names is <b>not yet enforced</b>; hold to it by hand until the checker lands.
 *
 * <p>The two markers are <b>not symmetric</b> -- see {@link ForegroundSafe} for why it, not this
 * one, is the strictly stronger guarantee.
 *
 * <p>Orthogonal to {@code @ThreadSafe} (JSR-305): that annotation says concurrent calls from
 * multiple callers are safe; this one says which category of caller is valid at all -- background
 * threads (there may be more than one), never the foreground/application thread. A class can, and
 * often will, carry both.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): every method of this type is background-only
 * unless a method-level {@link ForegroundSafe} widens it.
 *
 * <p><b>On a method</b> ({@link ElementType#METHOD}): this method specifically is background-only,
 * regardless of what the enclosing type declares -- a method-level marker always wins over the
 * type-level one.
 *
 * <p><b>Inheritance direction.</b> An override may only narrow a supertype's declared cost, never
 * widen it -- the same variance rule as a covariant return type, applied to a cost contract instead
 * of a value type. A method overriding a {@code @BackgroundOnly} supertype/interface method may
 * itself be marked {@link ForegroundSafe} if that override happens to be cheap: every caller
 * holding a reference typed to the supertype already assumed the worse (background-only) case, so a
 * cheaper override can't surprise them. The reverse can't be done safely: overriding a {@link
 * ForegroundSafe} or unannotated supertype method and marking the override {@code @BackgroundOnly}
 * breaks the promise for every existing caller holding a supertype-typed reference, without their
 * code changing at all -- this is a violation at the declaration site itself, independent of
 * whether any foreground call site currently exists in the diff.
 *
 * <p><b>Checker contract.</b> The rule below is written to be machine-checkable -- by a future
 * static checker, or in the meantime by an AI reviewer (see the {@code dd-apm-sdk-review} skill's
 * performance override, addendum J16) -- without needing to read this class's prose above.
 *
 * <ul>
 *   <li><b>Trigger:</b> a call site in code that is unannotated or {@link ForegroundSafe} and is
 *       reachable from an application/foreground call site (a span lifecycle method, an
 *       instrumentation advice body, anything on the app thread), into a symbol (type or method)
 *       annotated {@code @BackgroundOnly}.
 *   <li><b>Not a trigger:</b> a call from code that is itself {@code @BackgroundOnly} -- calls
 *       between background-only code are fine, only the foreground-to-background crossing is the
 *       violation. Also not a trigger: a call into a symbol that carries neither marker -- the
 *       absence of an annotation is not itself a finding, only a call that provably crosses a
 *       declared {@code @BackgroundOnly} boundary counts.
 *   <li><b>Violation example:</b> a span-lifecycle method or advice body calling {@code
 *       sharedUtf8Cache.getUtf8(value)} where {@code sharedUtf8Cache}'s declared type is annotated
 *       {@code @BackgroundOnly}.
 *   <li><b>Compliant example:</b> the same call made only from the background serializer thread
 *       that owns the cache, or from a method itself marked {@code @BackgroundOnly}.
 *   <li><b>Also a trigger, at the declaration site:</b> a method overriding a {@link
 *       ForegroundSafe} or unannotated supertype/interface method that marks the override
 *       {@code @BackgroundOnly} -- see "Inheritance direction" above. Flag this the moment it
 *       appears; it does not require a foreground call site to exist yet.
 *   <li><b>Out of scope (v1):</b> resolution is grep-only today (there is no APT-generated manifest
 *       yet -- {@code APMLP-1645}), so a callee outside the diff costs a grep per unfamiliar symbol
 *       rather than a lookup; reflection and dynamic-proxy call sites are not resolved at all.
 *   <li><b>Propagation across an interface boundary</b> (concrete implementation to the interface
 *       method it implements, and one hop further into a default method that calls it) is a bounded
 *       extension of the direct-call trigger above, not a separate rule -- see the {@code
 *       dd-apm-sdk-review} skill's performance override, addendum {@code background-only-contract},
 *       for its exact two-hop scope.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BackgroundOnly {}
