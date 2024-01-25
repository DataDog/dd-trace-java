package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import javax.annotation.Nonnull;

public class HardcodedSecretModuleImpl extends SinkModuleBase implements HardcodedSecretModule {

  public HardcodedSecretModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onHardcodedSecret(
      @Nonnull final String value,
      @Nonnull final String method,
      @Nonnull final String clazz,
      final int currentLine) {

    reporter.report(
        null,
        new Vulnerability(
            VulnerabilityType.HARDCODED_SECRET,
            Location.forClassAndMethodAndLine(clazz, method, currentLine),
            new Evidence(value)));
  }
}
