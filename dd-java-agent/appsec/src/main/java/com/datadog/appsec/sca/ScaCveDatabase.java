package com.datadog.appsec.sca;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
          "SCA Reachability: {} not found on classpath — no vulnerabilities will be tracked",
          RESOURCE_PATH);
      return new ScaCveDatabase(Collections.emptyMap());
    }
    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return parse(reader);
    } catch (Exception e) {
      log.error(
          "SCA Reachability: failed to parse {} — no vulnerabilities will be tracked",
          RESOURCE_PATH,
          e);
      return new ScaCveDatabase(Collections.emptyMap());
    }
  }

  static ScaCveDatabase parse(java.io.Reader reader) throws IOException {
    Moshi moshi = new Moshi.Builder().build();
    Type rootType = Types.newParameterizedType(Map.class, String.class, Object.class);
    JsonAdapter<Map<String, Object>> adapter = moshi.adapter(rootType);

    String content = readAll(reader);
    Map<String, Object> root = adapter.fromJson(content);
    if (root == null) {
      throw new IOException("sca_cves.json is empty");
    }

    List<?> rawEntries = (List<?>) root.get("entries");
    if (rawEntries == null) {
      return new ScaCveDatabase(Collections.emptyMap());
    }

    Map<String, List<ScaEntry>> index = new HashMap<>();
    int entryCount = 0;

    for (Object rawEntry : rawEntries) {
      Map<?, ?> entryMap = (Map<?, ?>) rawEntry;
      ScaEntry entry = ScaEntry.fromMap(entryMap);
      if (entry == null) continue;
      entryCount++;

      for (ScaSymbol symbol : entry.symbols()) {
        index.computeIfAbsent(symbol.className(), k -> new ArrayList<>()).add(entry);
      }
    }

    log.debug(
        "SCA Reachability: loaded {} entries, {} unique class symbols", entryCount, index.size());
    return new ScaCveDatabase(Collections.unmodifiableMap(index));
  }

  /** Returns the entries associated with the given JVM internal class name, or null if none. */
  public List<ScaEntry> entriesForClass(String internalClassName) {
    return index.get(internalClassName);
  }

  public boolean isEmpty() {
    return index.isEmpty();
  }

  public int size() {
    return index.size();
  }

  private static String readAll(java.io.Reader reader) throws IOException {
    StringBuilder sb = new StringBuilder();
    char[] buf = new char[8192];
    int n;
    while ((n = reader.read(buf)) != -1) {
      sb.append(buf, 0, n);
    }
    return sb.toString();
  }
}
