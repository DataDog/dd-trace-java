package com.datadog.iast.sink;

import static com.datadog.iast.util.HttpHeader.AUTHORIZATION;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.InsecureAuthProtocolModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;

public class InsecureAuthProtocolModuleImpl extends SinkModuleBase
    implements InsecureAuthProtocolModule {

  private static final String BASIC = "Basic";
  private static final String DIGEST = "Digest";

  public InsecureAuthProtocolModuleImpl(Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onHeader(@Nonnull String name, String value) {
    if (value == null || !AUTHORIZATION.matches(name)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    String insecureProtocol =
        value.startsWith(BASIC) ? BASIC : value.startsWith(DIGEST) ? DIGEST : null;
    if (insecureProtocol != null) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      final Evidence result =
          new Evidence(String.format("Found Authorization %s in header", insecureProtocol));
      report(span, VulnerabilityType.INSECURE_AUTH_PROTOCOL, result);
    }
  }
}
