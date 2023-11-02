package com.datadog.iast.model.json;

import com.datadog.iast.model.Vulnerability;
import java.util.List;
import javax.annotation.Nullable;

public class TruncatedVulnerabilities {

  @Nullable private final List<Vulnerability> vulnerabilities;

  public TruncatedVulnerabilities(@Nullable final List<Vulnerability> vulnerabilities) {
    this.vulnerabilities = vulnerabilities;
  }

  @Nullable
  public List<Vulnerability> getVulnerabilities() {
    return vulnerabilities;
  }
}
