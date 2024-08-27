package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.UntrustedDeserializationModule;
import javax.annotation.Nullable;

public class UntrustedDeserializationModuleImpl extends SinkModuleBase
    implements UntrustedDeserializationModule {

  public UntrustedDeserializationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onObject(@Nullable Object object) {
    if (object == null) {
      return;
    }
    checkInjection(VulnerabilityType.UNTRUSTED_DESERIALIZATION, object);
  }
}
