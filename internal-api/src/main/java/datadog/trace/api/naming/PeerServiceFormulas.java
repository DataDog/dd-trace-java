package datadog.trace.api.naming;

import static datadog.trace.api.KnownTags.DD_PEER_SERVICE_REMAPPED_FROM;
import static datadog.trace.api.KnownTags.DD_PEER_SERVICE_SOURCE;
import static datadog.trace.api.KnownTags.PEER_SERVICE;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.bootstrap.instrumentation.api.TagReader;
import datadog.trace.bootstrap.instrumentation.api.TagWriter;
import java.util.Map;

/** Shared pieces of the peer-service formulas. */
public final class PeerServiceFormulas {
  /** What every peer-service formula produces, all or nothing. */
  public static final long[] TARGETS = {
    PEER_SERVICE, DD_PEER_SERVICE_SOURCE, DD_PEER_SERVICE_REMAPPED_FROM
  };

  private PeerServiceFormulas() {}

  /**
   * The known-tag ids of precursor tag names, resolved once when a formula is built.
   *
   * @throws IllegalStateException if a precursor isn't a known tag, since formulas read known tags
   *     only
   */
  public static long[] idsOf(String[] precursors) {
    long[] ids = new long[precursors.length];
    for (int i = 0; i < precursors.length; ++i) {
      long id = KnownTagCodec.keyOf(precursors[i]);
      if (id == 0) {
        throw new IllegalStateException(
            "peer-service precursor is not a known tag: " + precursors[i]);
      }
      ids[i] = id;
    }
    return ids;
  }

  /**
   * Takes the first precursor that is set, as {@code PeerServiceNamingV1.resolveBy} does, and
   * writes it with its source and any mapping.
   */
  public static void resolveBy(
      TagReader in, TagWriter out, long[] ids, String[] names, Map<String, String> mapping) {
    for (int i = 0; i < ids.length; ++i) {
      Object value = in.get(ids[i]);
      if (value != null) {
        write(out, value, names[i], mapping);
        return;
      }
    }
  }

  /** Writes a peer service and its source, then the mapping if one applies. */
  public static void write(
      TagWriter out, Object value, String source, Map<String, String> mapping) {
    String mapped = mapping.isEmpty() ? null : mapping.get(value);
    if (mapped != null) {
      out.set(PEER_SERVICE, mapped);
      out.set(DD_PEER_SERVICE_REMAPPED_FROM, value);
    } else {
      out.set(PEER_SERVICE, value);
    }
    out.set(DD_PEER_SERVICE_SOURCE, source);
  }
}
