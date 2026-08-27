package datadog.trace.util;

import java.util.function.Consumer;
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

  public void ifPresentOrElse(Consumer<? super T> action, Runnable emptyAction) {
    if (value != null) {
      action.accept(value);
    } else {
      emptyAction.run();
    }
  }
}
