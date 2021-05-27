package com.datadog.appsec;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;

import java.util.Set;

@SuppressWarnings("rawtypes")
public interface Callback {

  Set<Address> getRequiredAddresses();
  Flow.ResultFlow onDataAvailable(RequestContext ctx, DataBundle dataBundle, boolean transientData);

}
