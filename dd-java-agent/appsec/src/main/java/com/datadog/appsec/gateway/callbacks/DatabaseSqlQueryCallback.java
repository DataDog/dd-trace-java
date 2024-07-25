package com.datadog.appsec.gateway.callbacks;

import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_0_2;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.telemetry.RuleType;
import java.util.function.BiFunction;

public class DatabaseSqlQueryCallback implements BiFunction<RequestContext, String, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public DatabaseSqlQueryCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, String sql) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = subscribersCache.getDbSqlQuerySubInfo();
      if (subInfo == null) {
        subInfo =
            producerService.getDataSubscribers(KnownAddresses.DB_TYPE, KnownAddresses.DB_SQL_QUERY);
        subscribersCache.setDbSqlQuerySubInfo(subInfo);
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2)
              .add(KnownAddresses.DB_TYPE, ctx.getDbType())
              .add(KnownAddresses.DB_SQL_QUERY, sql)
              .build();
      try {
        GatewayContext gwCtx = new GatewayContext(false, RuleType.SQL_INJECTION);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        subscribersCache.setDbSqlQuerySubInfo(null);
      }
    }
  }
}
