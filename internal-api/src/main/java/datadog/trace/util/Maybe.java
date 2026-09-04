package datadog.trace.util;

import datadog.trace.api.function.NoEscape;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ObjLongConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Candidate return shape for fallible operations (e.g. a table's capacity-refusing {@code
 * tryGetOrCreate}), evaluating whether it can be allocation-free under escape analysis.
 *
 * <p>Deliberately shaped against the {@code Optional}-style merge-with-singleton pitfall: {@link
 * #of} is the only allocation site, always allocates (never returns a shared instance), and holds a
 * plain nullable field. See {@code EscapeShapeBenchmark}'s {@code phiWithStatic} arm for why an
 * {@code EMPTY} singleton would cost 8 B/op on every JDK measured, including 25.
 *
 * <p>This shape -- one allocation site, a plain nullable field, no singleton merge -- scalar-
 * replaces on ordinary escape analysis, on JDK 8/11/17/25, no JDK-21+ {@code
 * ReduceAllocationMerges} needed (see {@code EscapeShapeBenchmark}). The discipline required of a
 * caller is that the wrapping method itself construct a {@code Maybe} at exactly one call site (fed
 * by a plain nullable local merged through ordinary branches, or by delegating to an
 * already-nullable-returning method) rather than once per {@code return} statement -- multiple
 * construction sites inline into a multi-producer phi that fails scalar replacement on JDK
 * 8/11/17/21 (measured 16 B/op, {@code MaybeUsagePatternsBenchmark#badMultiConstructionSite}) once
 * the refusal branch is reachable. On JDK 25, {@code ReduceAllocationMerges} collapses this
 * specific shape -- two branches allocating the same final type with identical field layout -- back
 * down to 0 B/op; do not rely on that JDK-25-only behavior, since it is exactly the kind of
 * EA-dependent elision that can regress silently the moment the two branches stop being trivially
 * mergeable (e.g. one branch gains extra state). See {@code EscapeShapeBenchmark}'s {@code
 * phiOfTwoAllocations} arm, which uses two distinct interface implementations rather than one
 * concrete type and therefore fails to scalar-replace on every JDK including 25 -- a different,
 * stronger failure mode than the one demonstrated here.
 *
 * <p>{@link NoEscape}: the scalar-replacement discipline above only holds while a {@code Maybe} is
 * constructed, consumed (typically via {@link #update}/{@link #getOrNull}), and discarded rather
 * than stored -- returning one, or passing it along to be consumed further downstream, is fine.
 * Assigning an instance to a field or collection forces the JIT to materialize it as a real,
 * permanent allocation, defeating the reason it exists.
 */
@NoEscape
public final class Maybe<T> {
  @Nullable private final T value;

  private Maybe(@Nullable T value) {
    this.value = value;
  }

  @Nonnull
  public static <T> Maybe<T> of(@Nullable T value) {
    return new Maybe<>(value);
  }

  /**
   * Convenience form for the common shape {@code Maybe.of(receiver.someNullableMethod(args))}:
   * {@code Maybe.of(receiver, r -> r.someNullableMethod(args))}. Useful when {@code receiver} would
   * otherwise have to be re-evaluated or named twice at the call site.
   *
   * <p>Unlike the single-arg {@link #of}, {@code fn} here is typically a <em>capturing</em> lambda
   * -- it closes over whatever local arguments the caller's method has in scope, so a fresh lambda
   * instance is created on every invocation (capturing lambdas are never cached the way a
   * non-capturing lambda's singleton instance commonly is) -- which makes it a second heap-object
   * candidate distinct from the {@code Maybe} itself. That freshly-allocated capturing lambda still
   * scalar-replaces as reliably as a plain delegating method call does, for the shape actually
   * measured (JDK 8/11/17/25): a monomorphic receiver and a {@code fn} that is applied exactly once
   * and does not itself escape (e.g. by being stored or passed further). If {@code fn} itself
   * captures something that must be freshly allocated per call (e.g. a non-singleton creator), that
   * allocation is real regardless of what happens to the lambda wrapping it.
   */
  @Nonnull
  public static <R, T> Maybe<T> of(R receiver, @Nonnull Function<? super R, ? extends T> fn) {
    return new Maybe<>(fn.apply(receiver));
  }

  public boolean isPresent() {
    return value != null;
  }

  /**
   * Raw accessor -- named to make the null case unmissable at the call site, rather than {@code
   * orElse}/{@code get}, neither of which says so on its own.
   */
  @Nullable
  public T getOrNull() {
    return value;
  }

  /**
   * Primary intended usage: a guard in front of mutation, e.g. {@code
   * table.tryGetOrCreateAsTry(key, FooEntry::new).update(FooEntry::inc)}. No-op if the operation
   * was refused (table full) rather than throwing or requiring the caller to branch on {@link
   * #isPresent()} first.
   */
  public void update(Consumer<? super T> mutator) {
    if (value != null) {
      mutator.accept(value);
    }
  }

  /**
   * Generic-context form of {@link #update(Consumer)}, for callers that already have a reusable,
   * non-capturing {@code BiConsumer} (typically a {@code static final}) plus whatever context it
   * needs -- {@code (value, context)} to stay consistent with the primitive-context overloads
   * below, at the cost of departing from {@code Hashtable#forEach}'s {@code (context, entry)}
   * convention.
   */
  public <C> void update(C context, BiConsumer<? super T, ? super C> mutator) {
    if (value != null) {
      mutator.accept(value, context);
    }
  }

  /**
   * Primitive-context form of {@link #update(Consumer)}, for the common case where the mutation
   * needs one caller-supplied number (e.g. a duration or count) and boxing it into a captured
   * {@code Long}/generic-context object would be the actual per-call allocation. This exists so a
   * table wrapping a fallible lookup in {@code Maybe} pays for this shape once, here, instead of
   * once per mutator-flavor per table type -- see {@code Hashtable#tryGetOrUpdate}'s {@code
   * ObjLongConsumer} overload for the caller-side problem this replaces.
   *
   * <p>Deliberately the <em>only</em> primitive-context overload. An {@code int}/{@code
   * double}/{@code boolean} sibling was tried and reverted: Java's overload resolution can pick
   * cleanly between a primitive overload and the generic {@link #update(Object, BiConsumer)} form
   * for a reference-typed argument (boxing is only considered once no non-boxing candidate
   * applies), but that guarantee does not extend to a second primitive overload -- {@code update(1,
   * lambda)} is ambiguous between {@code int} and {@code long} even with no {@code double} overload
   * in the picture, because {@link ObjIntConsumer} and {@link ObjLongConsumer} are unrelated
   * interfaces and JLS 15.12.2.5's most-specific-method rule requires every parameter position to
   * agree, not just the numeric one. Confirmed by direct compilation, not just JLS reading: an
   * inline lambda call breaks as soon as a second primitive overload exists. A plain {@code int}
   * argument still widens to {@code long} for free at this single overload -- callers are not
   * required to have a {@code long} in hand.
   */
  public void update(long context, ObjLongConsumer<? super T> mutator) {
    if (value != null) {
      mutator.accept(value, context);
    }
  }

  public void ifPresentOrElse(Consumer<? super T> action, Runnable emptyAction) {
    if (value != null) {
      action.accept(value);
    } else {
      emptyAction.run();
    }
  }
}
