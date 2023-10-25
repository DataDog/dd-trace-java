package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

@CallSite(spi = IastCallSites.class)
public class HttpServletRequestInputStreamCallSite {

  @Source(SourceTypes.REQUEST_BODY)
  @CallSite.After(
      "javax.servlet.ServletInputStream javax.servlet.http.HttpServletRequest.getInputStream()")
  @CallSite.After(
      "javax.servlet.ServletInputStream javax.servlet.http.HttpServletRequestWrapper.getInputStream()")
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
