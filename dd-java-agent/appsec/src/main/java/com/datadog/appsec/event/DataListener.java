package com.datadog.appsec.event;

import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;

public interface DataListener extends OrderedCallback {
  void onDataAvailable(
      ChangeableFlow flow,
      AppSecRequestContext context,
      DataBundle dataBundle,
      GatewayContext gatewayContext);
}
