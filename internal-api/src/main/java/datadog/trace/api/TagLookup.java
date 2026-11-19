package datadog.trace.api;

import java.util.Arrays;

/**
 * A fixed set of tag names, resolved once, for a consumer that reads the same tags from every span
 * (client-side stats reads its peer tags from each one). Each name is resolved to its known-tag id,
 * or marked custom, and to the ids it falls back to on inbound and outbound spans (see {@link
 * KnownTagCodec#directionalKeyOf}), so reading the set costs no name resolution per span. The slot
 * bits of all of those ids are folded into one mask per level, which lets a dense store rule out
 * the whole set with a mask test: a span that holds none of the tags pays a few bit operations, not
 * a lookup per name.
 *
 * <p>Build one when the set of names is known (e.g. when the agent advertises its peer tags) and
 * reuse it; it's immutable and safe to share across threads.
 */
public final class TagLookup {
  private static final long[] NO_IDS = new long[0];
  private static final int[] NO_INDEXES = new int[0];

  final String[] names;

  /** The known-tag id of each name, or 0 for a custom tag. */
  final long[] ids;

  /** Per known id: its serial, slot bit and level in a dense store, precomputed. */
  final char[] serials;

  final long[] bits;
  final int[] levels;

  /** The indexes of the custom names, which are looked up by name. */
  final int[] customIndexes;

  /** The id each name falls back to on an inbound span when the plain lookup misses, or 0. */
  final long[] inboundIds;

  /** The id each name falls back to on an outbound span when the plain lookup misses, or 0. */
  final long[] outboundIds;

  /** Whether any name falls back to a direction-scoped id. */
  final boolean hasDirectional;

  /** The slot bits of every span-level id above, plain and directional. */
  final long spanMask;

  /** The slot bits of every trace-level id above, plain and directional. */
  final long traceMask;

  private TagLookup(String[] names) {
    int n = names.length;
    this.names = names;
    long[] ids = new long[n];
    long[] inbound = new long[n];
    long[] outbound = new long[n];
    int[] custom = new int[n];
    int customCount = 0;
    boolean directional = false;
    long span = 0L;
    long trace = 0L;
    for (int i = 0; i < n; i++) {
      long id = KnownTagCodec.keyOf(names[i]);
      ids[i] = id;
      if (id == 0) {
        custom[customCount++] = i;
      }
      inbound[i] = KnownTagCodec.directionalKeyOf(names[i], KnownTagCodec.DIRECTION_INBOUND);
      outbound[i] = KnownTagCodec.directionalKeyOf(names[i], KnownTagCodec.DIRECTION_OUTBOUND);
      directional |= inbound[i] != 0 || outbound[i] != 0;
      for (long each : new long[] {id, inbound[i], outbound[i]}) {
        if (each != 0) {
          if (DenseIdStore.levelOf(each) == DenseIdStore.TRACE_LEVEL) {
            trace |= DenseIdStore.slotBit(each);
          } else {
            span |= DenseIdStore.slotBit(each);
          }
        }
      }
    }
    this.ids = n == 0 ? NO_IDS : ids;
    this.serials = new char[n];
    this.bits = new long[n];
    this.levels = new int[n];
    for (int i = 0; i < n; i++) {
      if (ids[i] != 0) {
        this.serials[i] = (char) KnownTagCodec.serialNum(ids[i]);
        this.bits[i] = DenseIdStore.slotBit(ids[i]);
        this.levels[i] = DenseIdStore.levelOf(ids[i]);
      }
    }
    this.customIndexes = customCount == 0 ? NO_INDEXES : Arrays.copyOf(custom, customCount);
    this.inboundIds = directional ? inbound : NO_IDS;
    this.outboundIds = directional ? outbound : NO_IDS;
    this.hasDirectional = directional;
    this.spanMask = span;
    this.traceMask = trace;
  }

  /** A lookup over {@code names}, read in this order. */
  public static TagLookup of(String... names) {
    return new TagLookup(names.clone());
  }

  /** The number of names. */
  public int size() {
    return names.length;
  }

  /** The {@code i}th name. */
  public String name(int i) {
    return names[i];
  }

  /** Whether any name is a custom tag, which can only be read by name. */
  public boolean hasCustom() {
    return customIndexes.length != 0;
  }

  /** The number of custom names. */
  public int customCount() {
    return customIndexes.length;
  }

  /** The index (into the names) of the {@code k}th custom name. */
  public int customIndex(int k) {
    return customIndexes[k];
  }

  /** The fallback id of the {@code i}th name for a span of {@code direction}, or 0. */
  public long directionalId(int i, int direction) {
    if (!hasDirectional) {
      return 0;
    }
    if (direction == KnownTagCodec.DIRECTION_INBOUND) {
      return inboundIds[i];
    }
    return direction == KnownTagCodec.DIRECTION_OUTBOUND ? outboundIds[i] : 0;
  }
}
