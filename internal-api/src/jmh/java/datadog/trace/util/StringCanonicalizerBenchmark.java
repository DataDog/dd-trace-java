package datadog.trace.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The cost of sharing the strings of one parse with {@link StringCanonicalizer}. Each operation
 * reads one document's text tokens out of a shared {@code char[]}, as a JSON parser hands them out
 * of its buffer, through a new canonicalizer, so the table's growth is part of the cost, as it is
 * per parse.
 *
 * <ul>
 *   <li>{@code newString}: one {@code String} per token, as a parser does without sharing. The
 *       baseline for time; it retains a copy of every repeat.
 *   <li>{@code canonicalizer}: {@link StringCanonicalizer#canonical(char[], int, int)}, which
 *       allocates only on the first occurrence of a text.
 *   <li>{@code hashMap}: {@code new String} plus a {@code HashMap.putIfAbsent}, the usual
 *       hand-rolled interner, which allocates a string per token and a node per distinct text.
 *   <li>{@code original}: dd-openfeature's first version (PR #13553), copied here as {@link
 *       Original}: the same table with an {@code h ^ (h >>> 16)} spread and no bounds, to show what
 *       the multiplicative spread and the probe bound change.
 * </ul>
 *
 * <p>Token shapes, each text repeated {@code repeats} times in shuffled order:
 *
 * <ul>
 *   <li>{@code keys}: generated names that differ in a trailing counter, like flag keys {@code
 *       scale-bulk-00042}. They have nearly equal hash codes.
 *   <li>{@code ids}: random 36-character ids, like the subject ids in a targeting list.
 *   <li>{@code collisions}: distinct strings that all share one hash code (built from {@code Aa}
 *       and {@code BB} blocks), as hostile input can be. The probe bound keeps the canonicalizer
 *       from comparing each against all the others.
 * </ul>
 *
 * <p>Report time and allocation ({@code -prof gc}) per operation, which is one document of {@code
 * distinct * repeats} tokens.
 */
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(4)
@State(Scope.Benchmark)
public class StringCanonicalizerBenchmark {
  @Param({"keys", "ids", "collisions"})
  public String shape;

  @Param({"1000", "10000"})
  public int distinct;

  @Param({"4"})
  public int repeats;

  private char[] buffer;
  private int[] offsets;
  private int[] lengths;

  @Setup
  public void setUp() {
    final List<String> texts = texts();
    final List<String> tokens = new ArrayList<>(texts.size() * repeats);
    for (int r = 0; r < repeats; r++) {
      tokens.addAll(texts);
    }
    Collections.shuffle(tokens, new Random(7));
    int total = 0;
    for (final String token : tokens) {
      total += token.length();
    }
    buffer = new char[total];
    offsets = new int[tokens.size()];
    lengths = new int[tokens.size()];
    int at = 0;
    for (int i = 0; i < tokens.size(); i++) {
      final String token = tokens.get(i);
      token.getChars(0, token.length(), buffer, at);
      offsets[i] = at;
      lengths[i] = token.length();
      at += token.length();
    }
  }

  private List<String> texts() {
    final List<String> texts = new ArrayList<>(distinct);
    final Random random = new Random(42);
    switch (shape) {
      case "keys":
        for (int i = 0; i < distinct; i++) {
          texts.add(String.format("scale-bulk-%05d", i));
        }
        break;
      case "ids":
        for (int i = 0; i < distinct; i++) {
          final char[] id = new char[36];
          for (int c = 0; c < id.length; c++) {
            id[c] = (c == 8 || c == 13 || c == 18 || c == 23) ? '-' : hex(random.nextInt(16));
          }
          texts.add(new String(id));
        }
        break;
      case "collisions":
        collide("", 32 - Integer.numberOfLeadingZeros(distinct - 1), texts);
        texts.subList(distinct, texts.size()).clear();
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
    return texts;
  }

  private static char hex(final int digit) {
    return (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
  }

  /** All 2^blocks strings of "Aa" and "BB" blocks, which share one hash code. */
  private static void collide(final String prefix, final int blocks, final List<String> out) {
    if (blocks == 0) {
      out.add(prefix);
      return;
    }
    collide(prefix + "Aa", blocks - 1, out);
    collide(prefix + "BB", blocks - 1, out);
  }

  @Benchmark
  public void newString(final Blackhole blackhole) {
    for (int i = 0; i < offsets.length; i++) {
      blackhole.consume(new String(buffer, offsets[i], lengths[i]));
    }
  }

  @Benchmark
  public void canonicalizer(final Blackhole blackhole) {
    final StringCanonicalizer strings = new StringCanonicalizer();
    for (int i = 0; i < offsets.length; i++) {
      blackhole.consume(strings.canonical(buffer, offsets[i], lengths[i]));
    }
  }

  @Benchmark
  public void original(final Blackhole blackhole) {
    final Original strings = new Original();
    for (int i = 0; i < offsets.length; i++) {
      blackhole.consume(strings.canonical(buffer, offsets[i], lengths[i]));
    }
  }

  @Benchmark
  public void hashMap(final Blackhole blackhole) {
    final Map<String, String> strings = new HashMap<>();
    for (int i = 0; i < offsets.length; i++) {
      final String text = new String(buffer, offsets[i], lengths[i]);
      final String existing = strings.putIfAbsent(text, text);
      blackhole.consume(existing != null ? existing : text);
    }
  }

  /** dd-openfeature's first canonicalizer (PR #13553), for comparison only. */
  static final class Original {
    private String[] strings = new String[256];
    private int[] hashes = new int[256];
    private int size;

    String canonical(final char[] chars, final int offset, final int length) {
      int hash = 0;
      for (int i = 0; i < length; i++) {
        hash = 31 * hash + chars[offset + i];
      }
      final int mask = strings.length - 1;
      for (int slot = spread(hash) & mask; ; slot = (slot + 1) & mask) {
        final String existing = strings[slot];
        if (existing == null) {
          final String text = new String(chars, offset, length);
          strings[slot] = text;
          hashes[slot] = hash;
          if (++size * 2 > strings.length) {
            grow();
          }
          return text;
        }
        if (hashes[slot] == hash && matches(existing, chars, offset, length)) {
          return existing;
        }
      }
    }

    private void grow() {
      final String[] oldStrings = strings;
      final int[] oldHashes = hashes;
      strings = new String[oldStrings.length * 2];
      hashes = new int[oldStrings.length * 2];
      final int mask = strings.length - 1;
      for (int i = 0; i < oldStrings.length; i++) {
        if (oldStrings[i] != null) {
          int slot = spread(oldHashes[i]) & mask;
          while (strings[slot] != null) {
            slot = (slot + 1) & mask;
          }
          strings[slot] = oldStrings[i];
          hashes[slot] = oldHashes[i];
        }
      }
    }

    private static boolean matches(
        final String existing, final char[] chars, final int offset, final int length) {
      if (existing.length() != length) {
        return false;
      }
      for (int i = 0; i < length; i++) {
        if (existing.charAt(i) != chars[offset + i]) {
          return false;
        }
      }
      return true;
    }

    private static int spread(final int hash) {
      return hash ^ (hash >>> 16);
    }
  }
}
