package com.datadog.appsec.sca;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One entry from sca_cves.json: a vulnerability affecting a specific Maven artifact. */
public final class ScaEntry {

  private static final Logger log = LoggerFactory.getLogger(ScaEntry.class);

  private final String vulnId;
  private final String artifact;
  private final List<String> versionRanges;
  private final List<ScaSymbol> symbols;

  public ScaEntry(
      String vulnId, String artifact, List<String> versionRanges, List<ScaSymbol> symbols) {
    this.vulnId = vulnId;
    this.artifact = artifact;
    this.versionRanges = Collections.unmodifiableList(versionRanges);
    this.symbols = Collections.unmodifiableList(symbols);
  }

  /** GHSA identifier, e.g. {@code "GHSA-645p-88qh-w398"}. */
  public String vulnId() {
    return vulnId;
  }

  /** Maven coordinate, e.g. {@code "com.fasterxml.jackson.core:jackson-databind"}. */
  public String artifact() {
    return artifact;
  }

  /**
   * Version range strings from sca_cves.json, e.g. {@code ["< 2.6.7.3", ">= 2.7.0, < 2.7.9.5"]}.
   */
  public List<String> versionRanges() {
    return versionRanges;
  }

  public List<ScaSymbol> symbols() {
    return symbols;
  }

  /** Returns true if the given version falls within any of this entry's version ranges. */
  public boolean isVersionVulnerable(String version) {
    return VersionRangeParser.matchesAny(version, versionRanges);
  }

  @Nullable
  static ScaEntry fromMap(Map<?, ?> map) {
    try {
      String vulnId = (String) map.get("vuln_id");
      String artifact = (String) map.get("artifact");
      List<?> rawRanges = (List<?>) map.get("version_ranges");
      List<?> rawSymbols = (List<?>) map.get("symbols");

      if (vulnId == null || artifact == null || rawRanges == null || rawSymbols == null) {
        log.debug("SCA Reachability: skipping malformed entry: {}", map);
        return null;
      }

      List<String> versionRanges = new ArrayList<>(rawRanges.size());
      for (Object r : rawRanges) {
        versionRanges.add((String) r);
      }

      List<ScaSymbol> symbols = new ArrayList<>(rawSymbols.size());
      for (Object rawSymbol : rawSymbols) {
        Map<?, ?> symbolMap = (Map<?, ?>) rawSymbol;
        String className = (String) symbolMap.get("class");
        String method = (String) symbolMap.get("method");
        if (className == null) continue;
        symbols.add(new ScaSymbol(className, method));
      }

      if (symbols.isEmpty()) return null;
      return new ScaEntry(vulnId, artifact, versionRanges, symbols);
    } catch (Exception e) {
      log.debug("SCA Reachability: skipping malformed entry", e);
      return null;
    }
  }
}
