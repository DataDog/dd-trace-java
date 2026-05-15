package datadog.trace.api.tt;

import java.util.ArrayList;
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
 * List#isEmpty()} check when no patterns are configured, so this class is zero-allocation in the
 * disabled case.
 *
 * <p>Matching is case-insensitive on the candidate name and the supported wildcard alphabet is
 * limited to {@code *} (zero-or-more characters). The matcher is hand-rolled to avoid {@link
 * java.util.regex.Pattern} compilation on the request hot path.
 */
public final class TransactionTrackingPatterns {

  private static final Logger log = LoggerFactory.getLogger(TransactionTrackingPatterns.class);

  /** Shared empty snapshot — referenced when no patterns are configured. */
  private static final List<CompiledPattern> EMPTY = Collections.emptyList();

  private static volatile List<CompiledPattern> snapshot = EMPTY;

  private TransactionTrackingPatterns() {}

  /**
   * Re-compile the raw pattern list and atomically publish a new snapshot. A {@code null} or empty
   * input clears the snapshot back to the no-op default. Malformed entries (null/blank) are dropped
   * with a debug log; the remaining entries are kept.
   */
  public static void update(List<String> rawPatterns) {
    if (rawPatterns == null || rawPatterns.isEmpty()) {
      snapshot = EMPTY;
      return;
    }
    List<CompiledPattern> compiled = new ArrayList<>(rawPatterns.size());
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
      compiled.add(CompiledPattern.compile(trimmed));
    }
    if (compiled.isEmpty()) {
      snapshot = EMPTY;
    } else {
      snapshot = Collections.unmodifiableList(compiled);
    }
  }

  /** Fast no-allocation check used as the hot-path guard. */
  public static boolean isEmpty() {
    return snapshot.isEmpty();
  }

  /** Returns the current immutable snapshot. */
  public static List<CompiledPattern> currentSnapshot() {
    return snapshot;
  }

  /** True if {@code candidate} matches any compiled pattern in the current snapshot. */
  public static boolean matchesAny(String candidate) {
    if (candidate == null) {
      return false;
    }
    List<CompiledPattern> local = snapshot;
    if (local.isEmpty()) {
      return false;
    }
    String lowered = candidate.toLowerCase(Locale.ROOT);
    // noinspection ForLoopReplaceableByForEach -- avoid iterator allocation on the hot path
    for (int i = 0; i < local.size(); i++) {
      if (local.get(i).matchesLowercased(lowered)) {
        return true;
      }
    }
    return false;
  }

  /** Test-only: replaces the snapshot atomically. */
  public static void resetForTest() {
    snapshot = EMPTY;
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
