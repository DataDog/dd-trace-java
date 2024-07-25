package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.ObjectIntrospection;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;

public class GrpcServerRequestMessageCallback
    implements BiFunction<RequestContext, Object, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public GrpcServerRequestMessageCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, Object obj) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      EventProducerService.DataSubscriberInfo subInfo =
          subscribersCache.getGrpcServerRequestMsgSubInfo();
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE);
        subscribersCache.setGrpcServerRequestMsgSubInfo(subInfo);
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      Object convObj = ObjectIntrospection.convert(obj);
      DataBundle bundle =
          new SingletonDataBundle<>(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE, convObj);
      try {
        GatewayContext gwCtx = new GatewayContext(true);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        subscribersCache.setGrpcServerRequestMsgSubInfo(null);
      }
    }
  }
}
