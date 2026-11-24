package com.datadog.openfeature;

import java.util.List;

/**
 * The flags a provider loads: the flags that have any of the tags, or whose key matches any of the
 * key patterns. A selection with neither loads all the flags.
 *
 * <p>Set it with {@link Provider.Options#flagSelection(FlagSelection)}.
 */
public final class FlagSelection {
  List<String> tags;
  List<String> keyPatterns;

  /** Creates a selection with no tags and no key patterns. */
  public FlagSelection() {}

  /**
   * Sets the tags. Tags match exactly and case-sensitively, and a flag matches when it has any of
   * them.
   *
   * @param tags the tags, or {@code null} for none.
   * @return this selection.
   */
  public FlagSelection tags(final List<String> tags) {
    this.tags = tags;
    return this;
  }

  /**
   * Sets the key patterns. A pattern matches the whole flag key, case-sensitively: {@code *}
   * matches any run of characters, and every other character is literal.
   *
   * @param keyPatterns the key patterns, or {@code null} for none.
   * @return this selection.
   */
  public FlagSelection keyPatterns(final List<String> keyPatterns) {
    this.keyPatterns = keyPatterns;
    return this;
  }
}
