package datadog.trace.civisibility.codeowners;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves entries by rule priority, switching large rule sets from linear scans to fixed-prefix
 * buckets while retaining a fallback for patterns that cannot be indexed.
 */
final class EntryIndex {

  private static final int MIN_INDEX_SIZE = 512;

  private List<IndexedEntry> entries = new ArrayList<>();
  private Map<String, List<IndexedEntry>> entriesByKey;
  private List<IndexedEntry> unindexedEntries;
  private int indexableEntryCount;

  void add(Entry entry, int order) {
    IndexedEntry indexedEntry = new IndexedEntry(entry, order);
    if (entriesByKey != null) {
      index(indexedEntry);
    } else {
      entries.add(indexedEntry);
      if (entry.getIndexKey() != null) {
        indexableEntryCount++;
      }
      if (entries.size() >= MIN_INDEX_SIZE && indexableEntryCount * 2 >= entries.size()) {
        entriesByKey = new HashMap<>();
        unindexedEntries = new ArrayList<>();
        for (IndexedEntry existingEntry : entries) {
          index(existingEntry);
        }
        entries = Collections.emptyList();
      }
    }
  }

  @Nullable
  Entry find(String path) {
    IndexedEntry entry = findIndexedEntry(path);
    return entry != null ? entry.entry : null;
  }

  private @Nullable IndexedEntry findIndexedEntry(String path) {
    if (entriesByKey == null) {
      return findFirstMatch(entries, path);
    }
    IndexedEntry unindexedMatch = findFirstMatch(unindexedEntries, path);

    int firstSeparator = path.indexOf('/');
    if (firstSeparator < 0) {
      return unindexedMatch;
    }
    int secondSeparator = path.indexOf('/', firstSeparator + 1);
    int keyEnd = secondSeparator >= 0 ? secondSeparator : path.length();
    List<IndexedEntry> indexedEntries = entriesByKey.get(path.substring(0, keyEnd));
    IndexedEntry indexedMatch = findFirstMatch(indexedEntries, path);

    if (unindexedMatch == null) {
      return indexedMatch;
    }
    if (indexedMatch == null) {
      return unindexedMatch;
    }
    return indexedMatch.order > unindexedMatch.order ? indexedMatch : unindexedMatch;
  }

  private void index(IndexedEntry indexedEntry) {
    String indexKey = indexedEntry.entry.getIndexKey();
    if (indexKey != null) {
      entriesByKey.computeIfAbsent(indexKey, key -> new ArrayList<>()).add(indexedEntry);
    } else {
      unindexedEntries.add(indexedEntry);
    }
  }

  private static @Nullable IndexedEntry findFirstMatch(
      @Nullable List<IndexedEntry> entries, String path) {
    if (entries != null) {
      for (int i = entries.size() - 1; i >= 0; i--) {
        IndexedEntry entry = entries.get(i);
        if (entry.entry.getMatcher().consume(path, 0) >= 0) {
          return entry;
        }
      }
    }
    return null;
  }

  private static final class IndexedEntry {

    private final Entry entry;
    private final int order;

    private IndexedEntry(Entry entry, int order) {
      this.entry = entry;
      this.order = order;
    }
  }
}
