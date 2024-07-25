package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiConsumer;

public class DatabaseConnectionCallback implements BiConsumer<RequestContext, String> {

  @Override
  public void accept(RequestContext requestContext, String dbType) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return;
    }
    ctx.setDbType(dbType);
  }
}
