package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
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
 * monomorphic code per caller — a JIT optimization opportunity, not a hard allocation-free
 * guarantee (a {@code getOrCreate}-shaped consumer can still allocate on a miss) — C++-template-like
 * specialization, driven by the JIT rather than a code generator.
 *
 * <p>Two independent things determine whether this pays off, and they fail in different ways:
 *
 * <ul>
 *   <li><b>Devirtualization &amp; inlining.</b> The aim is to leverage traditional compiler
 *       dataflow analysis rather than relying on HotSpot-specific speculative and profile-guided
 *       optimizations (PGO). The recommended approaches are to create a {@code static final}
 *       constant referencing a singular instance, or to use an inline lambda expression. When
 *       deviating from this approach, JVMs can frequently still devirtualize and inline via
 *       speculative analysis or PGO, but the aim is to avoid falling back to those mechanisms. To
 *       verify the consumer actually inlines, use {@code -XX:+UnlockDiagnosticVMOptions
 *       -XX:+PrintInlining}. A more thorough check can use {@code -XX:+UnlockDiagnosticVMOptions
 *       -XX:+LogCompilation} to check the inlining reason and conditions more directly.
 *   <li><b>Capturing &amp; allocation.</b> As a precaution in the event the JIT doesn't fully
 *       inline, prefer a {@code static final} constant referencing a singular instance, or a
 *       <i>non-capturing</i> lambda. A capturing lambda typically optimizes just as well as a
 *       non-capturing one when it does inline — it can be devirtualized and inlined via the same
 *       dataflow analysis, and its allocation can be scalar-replaced away by escape analysis.
 *       Unfortunately, when inlining fails, escape analysis tends to fail with it, turning the
 *       capturing lambda into a repeated allocation — whereas a non-capturing lambda is turned into
 *       a singleton by HotSpot regardless of inlining. These optimizations aren't strictly
 *       guaranteed by the JLS — singleton reuse of a non-capturing lambda is permitted, not
 *       required (<a
 *       href="https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.27.4">JLS
 *       §15.27.4</a>); our primary aim is working well on Java 8+ HotSpot/Graal and their
 *       derivatives — other JVMs typically perform similar optimizations, but are not the primary
 *       target of these performance patterns. So as a precaution, prefer a non-capturing lambda
 *       whenever possible.
 * </ul>
 *
 * <p>Both point at the same practical rule: make the consuming method inline. When it does, either
 * constant shape wins on both axes. When it doesn't, a concrete-typed field or non-capturing lambda
 * still avoids allocating, but devirtualization is gone either way.
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
 * allocates, quietly losing the win. Verify the hot ones with {@code -XX:+UnlockDiagnosticVMOptions
 * -XX:+PrintInlining}.
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
 *       {@code @Strategy}-annotated abstract/interface type itself (including via {@code
 *       @Inherited} from a supertype), whose initializer names a concrete implementing class (e.g.
 *       {@code new MyStrategy()}) rather than the field being declared with that concrete class
 *       directly. Scoped to {@code static final} fields in v1; instance fields are out of scope
 *       until a real case needs them.
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
 *       decision, not a static property — verify with {@code -XX:+UnlockDiagnosticVMOptions
 *       -XX:+PrintInlining}), and whether a lambda is truly non-capturing. Capture status is
 *       visible on the classfile (a captured lambda's {@code invokedynamic} call site carries
 *       constructor arguments), so this is checkable later, not inherently unknowable — just
 *       deferred until a real case proves the honor-system insufficient. If added, this should be
 *       a softer signal than the type-target trigger above: capturing costs nothing when the
 *       consumer inlines and only degrades gracefully when it doesn't, unlike the type-target case,
 *       which has no such escape. Flag only the constant shapes above for now.
 * </ul>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.PARAMETER})
public @interface Strategy {

  /**
   * Marks a field or parameter as a deliberate, reviewed exception to {@link Strategy}'s
   * concrete-typed-field convention — held at an abstract/interface type on purpose, not by
   * oversight. HotSpot may still devirtualize calls through it via dynamic reasoning (profile-guided
   * speculation, or class-hierarchy analysis for an abstract type with a sole loaded implementor),
   * but calls through it have no static-reasoning devirtualization guarantee the way a
   * concrete-typed field does. {@code CLASS}-retained for the same reason {@link Strategy} itself
   * is: a future classfile-level checker must be able to see the exemption without access to
   * source, which a comment could never provide.
   */
  @Documented
  @Retention(RetentionPolicy.CLASS)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @interface DynamicDispatch {
    /** Why staying on dynamic dispatch here is intentional. */
    String value();
  }
}
