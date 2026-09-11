package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a <b>static-polymorphism strategy</b>: a stateless policy object that lets one shared
 * algorithm specialize to straight-line code per caller, without runtime virtual dispatch.
 *
 * <p><b>What "static polymorphism" means here.</b> Ordinary (dynamic) polymorphism resolves the
 * implementation at run time — an {@code invokevirtual}/{@code invokeinterface} that can go
 * megamorphic on a shared call site. Static polymorphism instead makes the implementation known to
 * the JIT: hold the strategy in a {@code static final} constant, keep its methods small, and let
 * the consuming method inline. The call site then sees the exact type, so the JIT devirtualizes the
 * strategy's calls and inlines them, and the one generic algorithm compiles to specialized,
 * monomorphic, allocation-free code per caller — C++-template-like specialization, driven by the
 * JIT rather than a code generator.
 *
 * <p>Two shapes of constant satisfy this, with different strength guarantees:
 *
 * <ul>
 *   <li><b>Concrete-typed field.</b> A {@code static final} field <i>declared with the concrete
 *       type</i> (not an abstract base or interface). The win here is <b>structural</b> — it
 *       follows from the exact-typed constant, not a speculative bet on class-hierarchy analysis or
 *       type profiling that a second implementation or a polluted profile could quietly undo.
 *   <li><b>Non-capturing lambda constant.</b> A lambda has no nameable concrete type, so it cannot
 *       satisfy the rule above literally — but the JVM caches a non-capturing lambda as a single
 *       instance, and the JIT's per-call-site inline caching profiles the actual type(s) it
 *       observes there, not just the declared type. A call site fed this one constant consistently
 *       gets speculatively devirtualized and inlined on that observed profile — in practice a
 *       reliable win, not a fragile one. The guarantee is still weaker than the concrete-typed
 *       field's, though: it is profile-dependent rather than purely static, so a call site that
 *       later also sees a second implementation (a shared, polymorphic call site) can deoptimize
 *       and fall back to virtual dispatch, where a concrete-typed field never could. Prefer a
 *       concrete-typed field when a call site might plausibly be shared across strategies; a lambda
 *       constant is fine when it won't be.
 * </ul>
 *
 * <p>This is a documentation-and-tooling marker; it changes no behavior. It exists to telegraph the
 * pattern to readers and to give a future checker something to verify. The discipline it names is
 * <b>not yet enforced</b> — hold to it by hand until the checker lands.
 *
 * <p><b>On a type</b> ({@link ElementType#TYPE}): this type is a strategy. To get the
 * specialization a caller must hold it in one of the two constant shapes above, and the consuming
 * method must inline so the call site sees the exact type. Keep the methods small so they inline.
 *
 * <p><b>On a parameter</b> ({@link ElementType#PARAMETER}): this parameter is a strategy slot. The
 * argument at each call site should be a {@code static final} constant or a <i>non-capturing</i>
 * lambda, so it stays a single monomorphic, allocation-free instance. A parameter can carry this
 * marker even when its type cannot — e.g. a {@code java.util.function.Function} slot we don't own.
 *
 * <p><b>The failure mode is silent.</b> Fed to a call site that also sees other implementations,
 * filled with a capturing lambda or a freshly constructed instance per call, or called from a site
 * that doesn't inline, it still compiles and runs correctly — it just stays megamorphic and/or
 * allocates, quietly losing the win. Verify the hot ones with {@code -XX:+PrintInlining}.
 *
 * <p>Retention is {@link RetentionPolicy#CLASS}, not {@code SOURCE}: a checker that only has the
 * compiled classfiles of a module defining a {@code @Strategy} type (as opposed to its source)
 * still needs to see the annotation when checking a <em>different</em>, dependent module's fields
 * and call sites. {@code CLASS} keeps it there without exposing it to runtime reflection, which
 * nothing needs.
 *
 * <p><b>Checker contract.</b> The rule below is written to be machine-checkable — by a future
 * static checker, or in the meantime by an AI reviewer — without needing to read this class's prose
 * above. Because the underlying rule is "should" rather than "must", a trigger is a presumptive
 * finding to raise, not an automatic failure.
 *
 * <ul>
 *   <li><b>Trigger (type target):</b> a {@code static final} field, in any class, declared with the
 *       {@code @Strategy}-annotated abstract/interface type itself, whose initializer names a
 *       concrete implementing class (e.g. {@code new MyStrategy()}) rather than the field being
 *       declared with that concrete class directly.
 *   <li><b>Trigger (parameter target):</b> the argument at a call site of a {@code @Strategy}
 *       parameter is neither a {@code static final} field reference nor a lambda expression /
 *       method reference — e.g. a freshly constructed instance built per call.
 *   <li><b>Not a trigger:</b> a local variable, an ordinary (non-{@code @Strategy}) method
 *       parameter, or a method return type. Also not a trigger: a field or argument satisfied by a
 *       non-capturing lambda or method reference — this is the weaker, speculative shape described
 *       above, accepted as compliant rather than flagged. Also not a trigger: a field or parameter
 *       annotated {@link DynamicDispatch @DynamicDispatch} — the deliberate, reviewed exception
 *       below.
 *   <li><b>Violation example (type target):</b> {@code private static final EntryStrategy S = new
 *       CaseInsensitiveStringStrategy();} — declared at the abstract base, not the concrete class.
 *   <li><b>Compliant example (type target):</b> {@code private static final
 *       CaseInsensitiveStringStrategy S = new CaseInsensitiveStringStrategy();}, or {@code private
 *       static final HashStrategy<Entry> S = e -> e.hash;} (lambda constant, speculative shape).
 *       Or, if staying on dynamic dispatch is a deliberate, reviewed exception:
 *       {@code @DynamicDispatch("<reason>") private final HashStrategy<E> hashStrat;}
 *   <li><b>Out of scope (v1):</b> whether the consuming method actually inlines (a JIT runtime
 *       decision, not a static property — verify with {@code -XX:+PrintInlining}), and whether a
 *       lambda is truly non-capturing beyond what the compiler already enforces. Flag only the
 *       constant shapes above; widen this contract only once a real case proves it insufficient.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.PARAMETER})
public @interface Strategy {

  /**
   * Marks a field or parameter as a deliberate, reviewed exception to {@link Strategy}'s
   * concrete-typed/lambda-constant discipline — held at an abstract/interface type on purpose, so
   * calls through it stay on dynamic dispatch, not by oversight. {@code CLASS}-retained for the
   * same reason {@link Strategy} itself is: a future classfile-level checker must be able to see
   * the exemption without access to source, which a comment could never provide.
   */
  @Documented
  @Retention(RetentionPolicy.CLASS)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @interface DynamicDispatch {
    /** Why staying on dynamic dispatch here is intentional. */
    String value();
  }
}
