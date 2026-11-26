package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.KnownTagCodec;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * An immutable table of derived-tag formulas: for each formula, the known tags it produces. A span
 * gets its table from its {@link SpanPrototype}, layered over the tracer's base table.
 *
 * <p>Targets and inputs are known tags only, so a target's slot bit ({@link #slotBit}) can mark it
 * in {@link #mayProduce}, the one-AND check a span makes when a read misses its store.
 */
public final class TagDerivations {
  /** The table with no formulas. */
  public static final TagDerivations EMPTY = new TagDerivations(new long[0][], new TagsFormula[0]);

  /** The most formulas one table can hold: a span tracks each formula's state in one bit. */
  public static final int MAX_FORMULAS = 64;

  private final long[][] targets;
  private final TagsFormula[] formulas;
  private final long targetMask;

  private TagDerivations(long[][] targets, TagsFormula[] formulas) {
    this.targets = targets;
    this.formulas = formulas;
    long mask = 0L;
    for (long[] group : targets) {
      for (long tagId : group) {
        mask |= slotBit(tagId);
      }
    }
    this.targetMask = mask;
  }

  /**
   * A known tag's slot bit, as the dense store uses it. Tags without a slot share bit 63.
   *
   * @param tagId a {@code KnownTags} id
   * @return the tag's bit
   */
  public static long slotBit(long tagId) {
    return 1L << KnownTagCodec.slotOf(tagId);
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isEmpty() {
    return formulas.length == 0;
  }

  public int size() {
    return formulas.length;
  }

  /**
   * The targets of formula {@code index}. Callers must not modify the array.
   *
   * @param index a formula's index, below {@link #size}
   * @return the known-tag ids it produces
   */
  public long[] targets(int index) {
    return targets[index];
  }

  public TagsFormula formula(int index) {
    return formulas[index];
  }

  /**
   * Cheaply checks whether some formula may produce a tag. A clear bit is a definite no; a set bit
   * means look it up with {@link #indexOf}, since tags can share a slot.
   *
   * @param tagId a {@code KnownTags} id
   * @return false if no formula produces the tag
   */
  public boolean mayProduce(long tagId) {
    return (targetMask & slotBit(tagId)) != 0L;
  }

  /**
   * Finds the formula that produces a tag.
   *
   * @param tagId a {@code KnownTags} id
   * @return the formula's index, or -1 if none produces the tag
   */
  public int indexOf(long tagId) {
    for (int i = 0; i < targets.length; ++i) {
      for (long target : targets[i]) {
        if (target == tagId) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * This table layered over {@code base}: a formula here replaces a base formula with the same
   * targets, as a method override replaces a superclass's.
   *
   * @param base the table this one extends
   * @return the combined table
   * @throws IllegalArgumentException if a formula here overlaps a base formula's targets without
   *     matching them exactly
   */
  public TagDerivations over(TagDerivations base) {
    if (base.isEmpty()) {
      return this;
    }
    if (this.isEmpty()) {
      return base;
    }
    return builder().addAll(base).addAll(this).build();
  }

  public static final class Builder {
    private final ArrayList<long[]> targets = new ArrayList<>();
    private final ArrayList<TagsFormula> formulas = new ArrayList<>();

    private Builder() {}

    /**
     * Adds a single-output formula.
     *
     * @param target the known tag it produces
     * @param formula computes the value, or null for absent
     * @return this builder
     */
    public Builder add(final long target, final TagFormula formula) {
      if (formula == null) {
        throw new IllegalArgumentException("formula must not be null");
      }
      return add(new long[] {target}, (in, out) -> out.set(target, formula.compute(in)));
    }

    /**
     * Adds a multiple-output formula. One with exactly the same targets as an existing formula
     * replaces it.
     *
     * @param targets the known tags it produces, all or nothing
     * @param formula computes the outputs
     * @return this builder
     * @throws IllegalArgumentException if a target is not a known tag, is repeated, or overlaps an
     *     existing formula's targets without matching them exactly
     */
    public Builder add(final long[] targets, final TagsFormula formula) {
      if (formula == null || targets == null || targets.length == 0) {
        throw new IllegalArgumentException("a formula needs at least one target");
      }
      long[] sorted = targets.clone();
      Arrays.sort(sorted);
      for (int i = 0; i < sorted.length; ++i) {
        if (!KnownTagCodec.isKnown(sorted[i])) {
          throw new IllegalArgumentException(
              "derived tag targets must be known tags: " + Long.toHexString(sorted[i]));
        }
        if (i > 0 && sorted[i] == sorted[i - 1]) {
          throw new IllegalArgumentException("repeated target: " + Long.toHexString(sorted[i]));
        }
      }
      for (int i = 0; i < this.targets.size(); ++i) {
        long[] existing = this.targets.get(i);
        if (overlaps(existing, sorted)) {
          if (Arrays.equals(existing, sorted)) {
            this.formulas.set(i, formula); // an override
            return this;
          }
          throw new IllegalArgumentException(
              "formula targets partly overlap another formula's; override all of them or none");
        }
      }
      this.targets.add(sorted);
      this.formulas.add(formula);
      return this;
    }

    Builder addAll(TagDerivations table) {
      for (int i = 0; i < table.size(); ++i) {
        add(table.targets(i), table.formula(i));
      }
      return this;
    }

    public TagDerivations build() {
      if (formulas.isEmpty()) {
        return EMPTY;
      }
      if (formulas.size() > MAX_FORMULAS) {
        throw new IllegalStateException("more than " + MAX_FORMULAS + " formulas");
      }
      return new TagDerivations(
          targets.toArray(new long[0][]), formulas.toArray(new TagsFormula[0]));
    }

    private static boolean overlaps(long[] a, long[] b) {
      for (long x : a) {
        for (long y : b) {
          if (x == y) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
