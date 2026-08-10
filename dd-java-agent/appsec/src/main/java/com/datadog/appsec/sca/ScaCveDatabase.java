package com.datadog.appsec.sca;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@code sca_cves.json} from the classpath and builds the runtime index used by {@link
 * ScaReachabilityTransformer}.
 *
 * <p>The primary index maps JVM internal class names (slashes) to the list of {@link ScaEntry}
 * objects that reference them. The transformer does an O(1) lookup on every class load using this
 * map.
 */
public final class ScaCveDatabase {

  private static final Logger log = LoggerFactory.getLogger(ScaCveDatabase.class);
  private static final String RESOURCE_PATH = "/sca_cves.json";
  private static final int READ_BUFFER_SIZE = 8192;

  private final Map<String, List<ScaEntry>> index;

  private ScaCveDatabase(Map<String, List<ScaEntry>> index) {
    this.index = index;
  }

  /**
   * Loads and parses {@code sca_cves.json} from the classpath.
   *
   * @return a populated database, or an empty one if the resource is missing or malformed
   */
  public static ScaCveDatabase load() {
    InputStream stream = ScaCveDatabase.class.getResourceAsStream(RESOURCE_PATH);
    if (stream == null) {
      log.info(
          "SCA Reachability: {} not found on classpath - no vulnerabilities will be tracked",
          RESOURCE_PATH);
      return new ScaCveDatabase(Collections.emptyMap());
    }
    // "UTF-8" string literal - java.nio.* is forbidden during premain
    try (InputStreamReader reader = new InputStreamReader(stream, "UTF-8")) {
      return parse(reader);
    } catch (Exception e) {
      log.error(
          "SCA Reachability: failed to parse {} - no vulnerabilities will be tracked",
          RESOURCE_PATH,
          e);
      return new ScaCveDatabase(Collections.emptyMap());
    }
  }

  static ScaCveDatabase parse(Reader reader) throws IOException {
    Moshi moshi = new Moshi.Builder().build();
    JsonAdapter<DatabaseJson> adapter = moshi.adapter(DatabaseJson.class);

    String content = readAll(reader);
    DatabaseJson root = adapter.fromJson(content);
    if (root == null || root.entries == null) {
      return new ScaCveDatabase(Collections.emptyMap());
    }

    Map<String, List<ScaEntry>> index = new HashMap<>();
    int entryCount = 0;

    for (EntryJson e : root.entries) {
      ScaEntry entry = toScaEntry(e);
      if (entry == null) {
        continue;
      }
      entryCount++;
      // Index once per unique class name: an entry with multiple symbols for the same class
      // (e.g. Yaml.load + Yaml.loadAll) must appear only once in the list, otherwise
      // processClass iterates it twice and injects duplicate bytecode callbacks.
      Set<String> seen = new HashSet<>();
      for (ScaSymbol symbol : entry.symbols()) {
        if (seen.add(symbol.className())) {
          index.computeIfAbsent(symbol.className(), k -> new ArrayList<>()).add(entry);
        }
      }
    }

    log.debug(
        "SCA Reachability: loaded {} entries, {} unique class symbols", entryCount, index.size());
    return new ScaCveDatabase(Collections.unmodifiableMap(index));
  }

  @Nullable
  private static ScaEntry toScaEntry(EntryJson e) {
    if (e.vulnId == null || e.artifact == null || e.versionRanges == null || e.symbols == null) {
      log.debug("SCA Reachability: skipping malformed entry: {}", e);
      return null;
    }
    List<ScaSymbol> symbols = new ArrayList<>(e.symbols.size());
    for (SymbolJson s : e.symbols) {
      if (s.className == null) {
        continue;
      }
      if (s.method == null) {
        log.debug("SCA Reachability: skipping symbol with null method in entry {}", e.vulnId);
        continue;
      }
      symbols.add(new ScaSymbol(s.className, s.method));
    }
    if (symbols.isEmpty()) return null;
    return new ScaEntry(e.vulnId, e.artifact, e.versionRanges, symbols);
  }

  /**
   * Returns the entries associated with the given JVM internal class name, or null if none.
   *
   * <p>TODO: consider replacing this HashMap with a trie (e.g. {@code ClassNameTrie.Builder}) if
   * the database grows significantly. {@code HashMap} lookup for {@code String} keys is O(length)
   * on the first call for a new instance because {@code String.hashCode()} is not yet cached; a
   * trie is also O(length) but can exit early on a prefix mismatch, which is the common case (most
   * loaded classes are not in the CVE database). The project already has a runtime-constructable
   * {@code ClassNameTrie.Builder} used by the debugger, so the infrastructure exists. Note that the
   * current trie infrastructure uses prefix/glob patterns, so exact-match semantics would need to
   * be verified before adopting it here.
   */
  public List<ScaEntry> entriesForClass(String internalClassName) {
    return index.get(internalClassName);
  }

  public boolean isEmpty() {
    return index.isEmpty();
  }

  public int size() {
    return index.size();
  }

  private static String readAll(Reader reader) throws IOException {
    StringBuilder sb = new StringBuilder();
    char[] buf = new char[READ_BUFFER_SIZE];
    int n;
    while ((n = reader.read(buf)) != -1) {
      sb.append(buf, 0, n);
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // JSON DTOs - only used during parsing, never exposed outside this class
  // ---------------------------------------------------------------------------

  static final class DatabaseJson {
    int version;
    @Nullable List<EntryJson> entries;
  }

  static final class EntryJson {
    @Json(name = "vuln_id")
    @Nullable
    String vulnId;

    @Nullable String artifact;

    @Json(name = "version_ranges")
    @Nullable
    List<String> versionRanges;

    @Nullable List<SymbolJson> symbols;
  }

  static final class SymbolJson {
    @Json(name = "class")
    @Nullable
    String className;

    @Nullable String method;
  }
}
