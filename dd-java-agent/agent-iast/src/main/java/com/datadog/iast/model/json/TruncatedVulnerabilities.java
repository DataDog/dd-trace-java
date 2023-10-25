package com.datadog.iast.model.json;

import com.datadog.iast.model.Vulnerability;
import java.util.List;

public class TruncatedVulnerabilities {

  private final List<Vulnerability> vulnerabilities;

  public TruncatedVulnerabilities(final List<Vulnerability> vulnerabilities) {
    this.vulnerabilities = vulnerabilities;
  }

  public List<Vulnerability> getVulnerabilities() {
    return vulnerabilities;
  }
}
