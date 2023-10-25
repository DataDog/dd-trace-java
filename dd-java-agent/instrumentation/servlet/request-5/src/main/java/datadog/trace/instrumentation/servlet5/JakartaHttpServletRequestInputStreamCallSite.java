package datadog.trace.instrumentation.servlet5;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

@CallSite(spi = IastCallSites.class)
public class JakartaHttpServletRequestInputStreamCallSite {

  @Source(SourceTypes.REQUEST_BODY)
  @CallSite.After(
      "jakarta.servlet.ServletInputStream jakarta.servlet.http.HttpServletRequest.getInputStream()")
  @CallSite.After(
      "jakarta.servlet.ServletInputStream jakarta.servlet.http.HttpServletRequestWrapper.getInputStream()")
  public static ServletInputStream afterGetInputStream(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final ServletInputStream inputStream) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObject(SourceTypes.REQUEST_BODY, inputStream);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetInputStream threw", e);
      }
    }
    return inputStream;
  }
}
