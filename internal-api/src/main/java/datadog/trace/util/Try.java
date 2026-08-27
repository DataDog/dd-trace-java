package datadog.trace.util;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ObjLongConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * APMLP-1799 spike -- candidate return shape for fallible operations (e.g. a table's
 * capacity-refusing {@code tryGetOrCreate}), evaluating whether it can be allocation-free under
 * escape analysis. Not wired into any real caller yet.
 *
 * <p>Deliberately shaped against the {@code Optional}-style merge-with-singleton pitfall: {@link
 * #of} is the only allocation site, always allocates (never returns a shared instance), and holds a
 * plain nullable field. See {@code EscapeShapeBenchmark}'s {@code phiWithStatic} arm for why an
 * {@code EMPTY} singleton would cost 8 B/op on every JDK measured, including 25.
 *
 * <p><b>Confirmed 2026-08-27</b> ({@code EscapeShapeBenchmark}, JDK 8/11/17/25): the shape here --
 * one allocation site, a plain nullable field, no singleton merge -- scalar-replaces on ordinary
 * escape analysis, no JDK-21+ {@code ReduceAllocationMerges} needed. The discipline required of a
 * caller is that the wrapping method itself construct a {@code Try} at exactly one call site (fed
 * by a plain nullable local merged through ordinary branches, or by delegating to an
 * already-nullable-returning method) rather than once per {@code return} statement -- multiple
 * construction sites inline into a multi-producer phi that fails scalar replacement on every JDK
 * 8-25 once the refusal branch is reachable. See {@code EscapeShapeBenchmark}'s {@code
 * phiOfTwoAllocations} arm for that failure mode in isolation.
 */
public final class Try<T> {
  @Nullable private final T value;

  private Try(@Nullable T value) {
    this.value = value;
  }

  @Nonnull
  public static <T> Try<T> of(@Nullable T value) {
    return new Try<>(value);
  }

  /**
   * Convenience form for the common shape {@code Try.of(receiver.someNullableMethod(args))}: {@code
   * Try.of(receiver, r -> r.someNullableMethod(args))}. Useful when {@code receiver} would
   * otherwise have to be re-evaluated or named twice at the call site.
   *
   * <p>Unlike the single-arg {@link #of}, {@code fn} here is typically a <em>capturing</em> lambda
   * -- it closes over whatever local arguments the caller's method has in scope -- which makes it a
   * second heap-object candidate distinct from the {@code Try} itself. <b>Confirmed 2026-08-27</b>
   * against a real capacity-refusing lookup method (JDK 8/11/17/25, both a common and a rare
   * refusal ratio): the capturing lambda scalar-replaces as reliably as a plain delegating method
   * call does -- see the Hashtable-integration follow-up for that benchmark and the full numbers.
   * That confirmation is specific to the shape actually measured: a monomorphic receiver and a
   * {@code fn} built once per call site (not a fresh lambda per invocation) and applied exactly
   * once. If {@code fn} itself captures something that must be freshly allocated per call (e.g. a
   * non-singleton creator), that allocation is real regardless of what happens to the lambda
   * wrapping it.
   */
  @Nonnull
  public static <R, T> Try<T> of(R receiver, @Nonnull Function<? super R, ? extends T> fn) {
    return new Try<>(fn.apply(receiver));
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
   * Primitive-{@code long}-context form of {@link #update(Consumer)}, for the common case where the
   * mutation needs one caller-supplied {@code long} (e.g. a duration or count) and boxing it into a
   * captured {@code Long}/generic-context object would be the actual per-call allocation. This
   * exists so a table wrapping a fallible lookup in {@code Try} pays for this shape once, here,
   * instead of once per mutator-flavor per table type -- see {@code Hashtable#tryGetOrUpdate}'s
   * {@code ObjLongConsumer} overload for the caller-side problem this replaces.
   */
  public void update(long context, ObjLongConsumer<? super T> mutator) {
    if (value != null) {
      mutator.accept(value, context);
    }
  }

  /** {@code int}-context sibling of {@link #update(long, ObjLongConsumer)}. */
  public void update(int context, ObjIntConsumer<? super T> mutator) {
    if (value != null) {
      mutator.accept(value, context);
    }
  }

  /**
   * {@code double}-context sibling of {@link #update(long, ObjLongConsumer)}. Not driven by a known
   * caller today; kept in step with the {@code int}/{@code double}/{@code long} specializations
   * {@code Stream}/{@code Optional} carry, on the same anticipated-future-use basis.
   */
  public void update(double context, ObjDoubleConsumer<? super T> mutator) {
    if (value != null) {
      mutator.accept(value, context);
    }
  }

  /**
   * {@code boolean}-context sibling of {@link #update(long, ObjLongConsumer)}. Unlike the other
   * primitive forms, this one breaks from the {@code Stream}/{@code Optional} precedent -- the JDK
   * never shipped a boolean specialization for either -- so {@link ObjBooleanConsumer} is a
   * hand-rolled interface rather than a reused JDK one.
   */
  public void update(boolean context, ObjBooleanConsumer<? super T> mutator) {
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
