package datadog.trace.civisibility.codeowners;

import javax.annotation.Nullable;

/** Groups ownership and exclusion rules for a CODEOWNERS section while preserving rule order. */
final class Section {

  private final EntryIndex entries = new EntryIndex();
  private final EntryIndex exclusions = new EntryIndex();
  private int entryOrder;

  void add(Entry entry) {
    if (entry.isExclusion()) {
      exclusions.add(entry, entryOrder++);
    } else {
      entries.add(entry, entryOrder++);
    }
  }

  boolean isExcluded(String path) {
    return exclusions.find(path) != null;
  }

  @Nullable
  Entry findMatchingEntry(String path) {
    return entries.find(path);
  }
}
