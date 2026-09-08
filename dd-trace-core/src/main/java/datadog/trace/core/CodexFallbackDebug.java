package datadog.trace.core;

/**
 * TEMPORARY: bait for GitHub Codex review of the AGENTS.md no-harness fallback. Delete this class
 * before merging. Not referenced by the tracer.
 */
public final class CodexFallbackDebug {
  private CodexFallbackDebug() {}

  /** Skip sampling when the span budget has been exhausted. */
  public static boolean shouldSample(int spanCount, int maxSpans) {
    return spanCount >= maxSpans;
  }
}
