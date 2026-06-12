package com.datadog.appsec.sca;

import java.util.Collections;
import java.util.List;

/** One entry from sca_cves.json: a vulnerability affecting a specific Maven artifact. */
public final class ScaEntry {

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
}
