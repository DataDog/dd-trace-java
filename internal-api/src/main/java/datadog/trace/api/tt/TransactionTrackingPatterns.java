package datadog.trace.api.tt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot of compiled "transaction tracking" extraction glob patterns delivered through
 * remote-config under the {@code APM_TRACING} product (field {@code tt_extraction_patterns}).
 *
 * <p>The hot path on every server request only does a single volatile read followed by an {@link
 * Snapshot#isEmpty()} check when no patterns are configured, so this class is zero-allocation in
 * the disabled case.
 *
 * <p>Patterns are partitioned at update time:
 *
 * <ul>
 *   <li>literal patterns (no {@code *}) go into a custom case-insensitive open-addressed hash set
 *       so that {@link #matchesAny(String)} is O(1) and does not allocate;
 *   <li>patterns containing at least one {@code *} go through {@link CompiledPattern#compile} and
 *       are matched linearly only when the literal lookup misses.
 * </ul>
 *
 * <p>Matching is case-insensitive on the candidate name and the supported wildcard alphabet is
 * limited to {@code *} (zero-or-more characters). The matcher is hand-rolled to avoid {@link
 * java.util.regex.Pattern} compilation on the request hot path.
 */
public final class TransactionTrackingPatterns {

  private static final Logger log = LoggerFactory.getLogger(TransactionTrackingPatterns.class);

  private static volatile Snapshot snapshot = Snapshot.EMPTY;

  private TransactionTrackingPatterns() {}

  /**
   * Re-compile the raw pattern list and atomically publish a new snapshot. A {@code null} or empty
   * input clears the snapshot back to the no-op default. Malformed entries (null/blank) are dropped
   * with a debug log; the remaining entries are kept.
   */
  public static void update(List<String> rawPatterns) {
    if (rawPatterns == null || rawPatterns.isEmpty()) {
      snapshot = Snapshot.EMPTY;
      return;
    }
    List<String> literalsLower = new ArrayList<>(rawPatterns.size());
    List<CompiledPattern> wildcards = new ArrayList<>();
    for (String raw : rawPatterns) {
      if (raw == null) {
        log.debug("Ignoring null tt_extraction_pattern entry");
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        log.debug("Ignoring blank tt_extraction_pattern entry");
        continue;
      }
      if (trimmed.indexOf('*') < 0) {
        literalsLower.add(trimmed.toLowerCase(Locale.ROOT));
      } else {
        wildcards.add(CompiledPattern.compile(trimmed));
      }
    }
    if (literalsLower.isEmpty() && wildcards.isEmpty()) {
      snapshot = Snapshot.EMPTY;
      return;
    }
    CaseInsensitiveStringSet literalSet =
        literalsLower.isEmpty() ? null : new CaseInsensitiveStringSet(literalsLower);
    List<CompiledPattern> wildcardList =
        wildcards.isEmpty()
            ? Collections.<CompiledPattern>emptyList()
            : Collections.unmodifiableList(wildcards);
    snapshot = new Snapshot(literalSet, wildcardList);
  }

  /** Fast no-allocation check used as the hot-path guard. */
  public static boolean isEmpty() {
    return snapshot.isEmpty();
  }

  /**
   * Returns a snapshot view of the currently configured patterns. The returned list concatenates
   * the literal patterns (rebuilt as {@link CompiledPattern} instances) and the wildcard patterns.
   * This is intended for diagnostics/tests only; production code uses {@link #matchesAny(String)}
   * and {@link #isEmpty()}.
   */
  public static List<CompiledPattern> currentSnapshot() {
    Snapshot local = snapshot;
    if (local.isEmpty()) {
      return Collections.emptyList();
    }
    List<CompiledPattern> view = new ArrayList<>();
    if (local.literals != null) {
      String[] table = local.literals.table;
      for (int i = 0; i < table.length; i++) {
        String entry = table[i];
        if (entry != null) {
          view.add(CompiledPattern.compile(entry));
        }
      }
    }
    view.addAll(local.wildcards);
    return Collections.unmodifiableList(view);
  }

  /** True if {@code candidate} matches any compiled pattern in the current snapshot. */
  public static boolean matchesAny(String candidate) {
    if (candidate == null) {
      return false;
    }
    Snapshot local = snapshot;
    if (local.literals != null && local.literals.contains(candidate)) {
      return true;
    }
    List<CompiledPattern> wildcards = local.wildcards;
    int n = wildcards.size();
    if (n == 0) {
      return false;
    }
    String lowered = candidate.toLowerCase(Locale.ROOT);
    for (int i = 0; i < n; i++) {
      if (wildcards.get(i).matchesLowercased(lowered)) {
        return true;
      }
    }
    return false;
  }

  /** Test-only: replaces the snapshot atomically. */
  public static void resetForTest() {
    snapshot = Snapshot.EMPTY;
  }

  /** Test-only: number of literal patterns in the current snapshot. */
  static int literalCountForTest() {
    Snapshot local = snapshot;
    return local.literals == null ? 0 : local.literals.size;
  }

  /** Test-only: number of wildcard patterns in the current snapshot. */
  static int wildcardCountForTest() {
    return snapshot.wildcards.size();
  }

  /** Immutable holder for the partitioned pattern set. */
  private static final class Snapshot {
    static final Snapshot EMPTY = new Snapshot(null, Collections.<CompiledPattern>emptyList());

    /** Null when there are no literal patterns; otherwise non-empty. */
    final CaseInsensitiveStringSet literals;

    /** Possibly-empty immutable list of wildcard-bearing patterns. */
    final List<CompiledPattern> wildcards;

    Snapshot(CaseInsensitiveStringSet literals, List<CompiledPattern> wildcards) {
      this.literals = literals;
      this.wildcards = wildcards;
    }

    boolean isEmpty() {
      return (literals == null || literals.isEmpty()) && wildcards.isEmpty();
    }
  }

  /**
   * Open-addressed, power-of-two-sized, linearly probed case-insensitive string set.
   *
   * <p>Keys are stored lowercased once at construction; {@link #contains(String)} does NOT allocate
   * and does NOT lowercase the candidate. The case-insensitive hash treats ASCII {@code A-Z} as
   * their lowercase counterparts; non-ASCII characters are hashed verbatim and rely on {@link
   * String#equalsIgnoreCase(String)} for correctness at the equality probe.
   */
  static final class CaseInsensitiveStringSet {
    final String[] table;
    final int mask;
    final int size;

    CaseInsensitiveStringSet(Collection<String> lowercasedKeys) {
      int n = lowercasedKeys.size();
      // capacity >= 8 and >= next-power-of-two of (2*n) to keep load factor <= 0.5
      int cap = 8;
      int target = Math.max(1, n) * 2;
      while (cap < target) {
        cap <<= 1;
      }
      String[] t = new String[cap];
      int m = cap - 1;
      int inserted = 0;
      for (String k : lowercasedKeys) {
        int slot = ciHash(k) & m;
        boolean duplicate = false;
        while (t[slot] != null) {
          if (t[slot].equalsIgnoreCase(k)) {
            duplicate = true;
            break;
          }
          slot = (slot + 1) & m;
        }
        if (!duplicate) {
          t[slot] = k;
          inserted++;
        }
      }
      this.table = t;
      this.mask = m;
      this.size = inserted;
    }

    boolean isEmpty() {
      return size == 0;
    }

    boolean contains(String candidate) {
      if (candidate == null || size == 0) {
        return false;
      }
      int slot = ciHash(candidate) & mask;
      while (true) {
        String entry = table[slot];
        if (entry == null) {
          return false;
        }
        if (entry.equalsIgnoreCase(candidate)) {
          return true;
        }
        slot = (slot + 1) & mask;
      }
    }

    private static int ciHash(String s) {
      int h = 0;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c >= 'A' && c <= 'Z') {
          c = (char) (c + 32);
        }
        h = 31 * h + c;
      }
      return h;
    }
  }

  /**
   * Compiled glob pattern. Supports only {@code *} (zero-or-more) wildcard. Comparison is
   * case-insensitive: the pattern is lowercased once at compile time and the candidate must be
   * lowercased by the caller before invoking {@link #matchesLowercased(String)}.
   */
  public static final class CompiledPattern {
    private final String original;
    private final String[] segments;
    private final boolean anchorStart;
    private final boolean anchorEnd;

    private CompiledPattern(
        String original, String[] segments, boolean anchorStart, boolean anchorEnd) {
      this.original = original;
      this.segments = segments;
      this.anchorStart = anchorStart;
      this.anchorEnd = anchorEnd;
    }

    static CompiledPattern compile(String raw) {
      String lower = raw.toLowerCase(Locale.ROOT);
      boolean anchorStart = !lower.startsWith("*");
      boolean anchorEnd = !lower.endsWith("*");
      // hand-rolled split on '*' that preserves empty segments only when needed
      List<String> parts = new ArrayList<>();
      int start = 0;
      for (int i = 0; i < lower.length(); i++) {
        if (lower.charAt(i) == '*') {
          if (i > start) {
            parts.add(lower.substring(start, i));
          }
          start = i + 1;
        }
      }
      if (start < lower.length()) {
        parts.add(lower.substring(start));
      }
      return new CompiledPattern(raw, parts.toArray(new String[0]), anchorStart, anchorEnd);
    }

    public String original() {
      return original;
    }

    /** Caller must pass an already-lowercased candidate. */
    public boolean matchesLowercased(String candidate) {
      // Pattern was just "*" / "***" etc. -> matches anything.
      if (segments.length == 0) {
        return true;
      }

      // Special case: literal pattern (no '*' at all) -> exact match.
      if (anchorStart && anchorEnd && segments.length == 1) {
        return candidate.equals(segments[0]);
      }

      int idx = 0;
      int firstFloating = 0;
      if (anchorStart) {
        String first = segments[0];
        if (!candidate.startsWith(first)) {
          return false;
        }
        idx = first.length();
        firstFloating = 1;
      }

      int endIdx = candidate.length();
      int lastFloating = segments.length; // exclusive
      if (anchorEnd) {
        String last = segments[segments.length - 1];
        int tailStart = candidate.length() - last.length();
        if (tailStart < idx || !candidate.regionMatches(tailStart, last, 0, last.length())) {
          return false;
        }
        endIdx = tailStart;
        lastFloating = segments.length - 1;
      }

      // walk remaining floating segments via indexOf, all must fit within [idx, endIdx)
      for (int segIndex = firstFloating; segIndex < lastFloating; segIndex++) {
        String seg = segments[segIndex];
        int found = candidate.indexOf(seg, idx);
        if (found < 0 || found + seg.length() > endIdx) {
          return false;
        }
        idx = found + seg.length();
      }
      return true;
    }
  }
}
