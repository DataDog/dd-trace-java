package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.ObjectIntrospection;
import com.datadog.appsec.event.data.SingletonDataBundle;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;

public class GrpcServerRequestMessageCallback
    implements BiFunction<RequestContext, Object, Flow<Void>> {

  private final EventProducerService producerService;
  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo grpcServerRequestMsgSubInfo;

  public GrpcServerRequestMessageCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(final RequestContext ctx_, final Object obj) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = grpcServerRequestMsgSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE);
        grpcServerRequestMsgSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      Object convObj = ObjectIntrospection.convert(obj);
      DataBundle bundle =
          new SingletonDataBundle<>(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE, convObj);
      try {
        return producerService.publishDataEvent(grpcServerRequestMsgSubInfo, ctx, bundle, true);
      } catch (ExpiredSubscriberInfoException e) {
        grpcServerRequestMsgSubInfo = null;
      }
    }
  }
}
