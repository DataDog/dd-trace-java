package datadog.trace.agent.tooling;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prototype diagnostics (only with {@code dd.resolver.declared-method-sieve.verify}): classifies
 * each transformation's matching as data or code, records which transformations ever matched, and
 * at exit estimates what lazily loading unmatched instrumentations would avoid.
 */
final class LazyLoadDiagnostics {
  private static final Logger log = LoggerFactory.getLogger(LazyLoadDiagnostics.class);

  static final class Entry {
    final String module;
    final String member;
    final String category;
    final String codeLeaf;

    Entry(String module, String member, String category, String codeLeaf) {
      this.module = module;
      this.member = member;
      this.category = category;
      this.codeLeaf = codeLeaf;
    }
  }

  private static final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
  private static final BitSet matched = new BitSet();
  private static boolean hooked;

  private LazyLoadDiagnostics() {}

  static synchronized void classify(
      int transformationId, String module, String member, String category, String codeLeaf) {
    entries.put(transformationId, new Entry(module, member, category, codeLeaf));
    if (!hooked) {
      hooked = true;
      Runtime.getRuntime()
          .addShutdownHook(new Thread(LazyLoadDiagnostics::report, "dd-lazy-load-diagnostics"));
    }
  }

  static void recordMatches(BitSet ids) {
    synchronized (matched) {
      matched.or(ids);
    }
  }

  private static void report() {
    Map<String, int[]> byCategory = new TreeMap<>(); // category -> {total, matched}
    Map<String, int[]> modules = new TreeMap<>(); // module -> {members, matched members}
    Map<String, Integer> codeLeaves = new TreeMap<>();
    synchronized (matched) {
      for (Map.Entry<Integer, Entry> e : entries.entrySet()) {
        boolean hit = matched.get(e.getKey());
        Entry entry = e.getValue();
        int[] c = byCategory.computeIfAbsent(entry.category, k -> new int[2]);
        c[0]++;
        if (hit) {
          c[1]++;
        }
        int[] m = modules.computeIfAbsent(entry.module, k -> new int[2]);
        m[0]++;
        if (hit) {
          m[1]++;
        }
        if (null != entry.codeLeaf) {
          String leaf =
              entry.codeLeaf.length() > 120 ? entry.codeLeaf.substring(0, 120) : entry.codeLeaf;
          codeLeaves.merge(leaf, 1, Integer::sum);
        }
      }
    }
    int modulesMatched = 0;
    for (int[] m : modules.values()) {
      if (m[1] > 0) {
        modulesMatched++;
      }
    }
    log.warn(
        "Lazy-load diagnostics: {} transformations in {} modules; {} modules matched at least once",
        entries.size(),
        modules.size(),
        modulesMatched);
    for (Map.Entry<String, int[]> c : byCategory.entrySet()) {
      log.warn(
          "Lazy-load diagnostics: category {}: {} transformations, {} matched",
          c.getKey(),
          c.getValue()[0],
          c.getValue()[1]);
    }
    codeLeaves.entrySet().stream()
        .sorted((a, b) -> b.getValue() - a.getValue())
        .limit(25)
        .forEach(
            e -> log.warn("Lazy-load diagnostics: code leaf x{}: {}", e.getValue(), e.getKey()));
  }
}
