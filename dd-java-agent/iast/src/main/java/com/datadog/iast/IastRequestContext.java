package com.datadog.iast;

import com.datadog.iast.model.Vulnerability;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class IastRequestContext {

  private final ConcurrentLinkedQueue<Vulnerability> vulnerabilities;

  public IastRequestContext() {
    this.vulnerabilities = new ConcurrentLinkedQueue<>();
  }

  public List<Vulnerability> getVulnerabilities() {
    return new ArrayList<>(vulnerabilities);
  }

  public void addVulnerability(final Vulnerability vulnerability) {
    vulnerabilities.add(vulnerability);
  }
}
