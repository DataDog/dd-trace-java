package com.datadog.appsec;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;

import java.util.List;

@SuppressWarnings("rawtypes")
public interface Callback {

  List<Address> getRequiredAddresses();
  Flow.ResultFlow onDataAvailable(RequestContext ctx, DataBundle dataBundle, boolean transientData);

}
