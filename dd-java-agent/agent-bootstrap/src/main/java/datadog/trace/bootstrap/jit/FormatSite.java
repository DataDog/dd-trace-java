package datadog.trace.bootstrap.jit;

/**
 * State of one rewritten {@code String.format} call site.
 *
 * <p>A site starts cold and uses the JDK. After {@link #COMPILE_THRESHOLD} calls it compiles the
 * format string it sees and uses that while the format stays the same. When the format changes it
 * starts counting again; after {@link #MAX_CHANGES} changes, or if the format isn't supported, it
 * uses the JDK for good.
 *
 * <p>Fields are plain: races can lose counts or changes, which only delays a decision. The compiled
 * format is immutable, so reading it without synchronization is safe.
 */
public final class FormatSite {
  static final int COMPILE_THRESHOLD = 16;
  static final int MAX_CHANGES = 4;

  /** The compiled format in use, or {@code null}. */
  CompiledFormat compiled;

  /** Calls counted since the site was created or the format last changed. */
  int calls;

  /** How many times the format changed after it was compiled. */
  int changes;

  /** Whether the site uses the JDK for good. */
  boolean disabled;

  public FormatSite() {}

  /** The compiled format to use for this call, or {@code null} to use the JDK. */
  static CompiledFormat compiledFor(FormatSite[] sites, int slot, String format) {
    FormatSite site = sites[slot];
    if (null != site) {
      CompiledFormat compiled = site.compiled;
      if (null != compiled && compiled.isFor(format)) {
        return compiled;
      }
      if (site.disabled) {
        return null;
      }
    }
    return record(sites, slot, format);
  }

  /** Counts a call that isn't using a compiled format; may compile it. */
  private static CompiledFormat record(FormatSite[] sites, int slot, String format) {
    FormatSite site = sites[slot];
    if (null == site) {
      site = new FormatSite();
      sites[slot] = site;
    }
    if (null == format) {
      return null; // the JDK throws
    }
    if (null != site.compiled) {
      // the format changed
      site.compiled = null;
      site.calls = 0;
      if (++site.changes > MAX_CHANGES) {
        site.disabled = true;
        return null;
      }
    }
    if (++site.calls < COMPILE_THRESHOLD) {
      return null;
    }
    CompiledFormat compiled = CompiledFormat.parse(format);
    if (null == compiled) {
      site.disabled = true;
    } else {
      site.compiled = compiled;
    }
    return compiled;
  }
}
