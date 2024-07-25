package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.Map;
import java.util.function.BiFunction;

public class GraphqlServerRequestMessageCallback
    implements BiFunction<RequestContext, Map<String, ?>, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public GraphqlServerRequestMessageCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, Map<String, ?> data) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      EventProducerService.DataSubscriberInfo subInfo =
          subscribersCache.getGraphqlServerRequestMsgSubInfo();
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.GRAPHQL_SERVER_ALL_RESOLVERS);
        subscribersCache.setGraphqlServerRequestMsgSubInfo(subInfo);
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new SingletonDataBundle<>(KnownAddresses.GRAPHQL_SERVER_ALL_RESOLVERS, data);
      try {
        GatewayContext gwCtx = new GatewayContext(true);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        subscribersCache.setGraphqlServerRequestMsgSubInfo(null);
      }
    }
  }
}
