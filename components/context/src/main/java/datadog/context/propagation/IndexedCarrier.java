package datadog.context.propagation;

/**
 * A carrier whose key/value pairs are already in memory and can be read by index, so a propagator
 * that reads them more than once can do so in place instead of copying them first. The composite
 * propagator hands its propagators one, holding the keys any of them reads.
 *
 * <p>Read-only, and valid only for the extraction it was handed to: don't keep a reference.
 */
public interface IndexedCarrier {
  /** The number of key/value pairs. */
  int size();

  /** The key of pair {@code index}, from 0 to {@link #size()} - 1. */
  String key(int index);

  /** The value of pair {@code index}, from 0 to {@link #size()} - 1, as a {@code String}. */
  String value(int index);

  /**
   * The value of pair {@code index}, possibly a transient view (see {@link TransientValueConsumer})
   * valid only during the extraction: parse it, or copy it to keep it. The default returns {@link
   * #value(int)}.
   */
  default CharSequence valueView(int index) {
    return value(index);
  }

  /**
   * The {@link HeaderSchema} constant of pair {@code index}'s key, or null if it has none (an
   * unknown key, or a key the carrier didn't classify). Lets a propagator switch on the constant
   * instead of comparing names; null keys must still be checked by name.
   */
  HeaderSchema header(int index);
}
