package com.datadog.appsec.gateway;

import com.datadog.appsec.api.security.ApiSecurityRequestSampler;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.callbacks.DatabaseConnectionCallback;
import com.datadog.appsec.gateway.callbacks.DatabaseSqlQueryCallback;
import com.datadog.appsec.gateway.callbacks.GraphqlServerRequestMessageCallback;
import com.datadog.appsec.gateway.callbacks.GrpcServerMethodCallback;
import com.datadog.appsec.gateway.callbacks.GrpcServerRequestMessageCallback;
import com.datadog.appsec.gateway.callbacks.MethodAndRawURICallback;
import com.datadog.appsec.gateway.callbacks.NewRequestHeaderCallback;
import com.datadog.appsec.gateway.callbacks.RequestBodyDoneCallback;
import com.datadog.appsec.gateway.callbacks.RequestBodyProcessedCallback;
import com.datadog.appsec.gateway.callbacks.RequestBodyStartCallback;
import com.datadog.appsec.gateway.callbacks.RequestClientSocketAddressCallback;
import com.datadog.appsec.gateway.callbacks.RequestEndedCallBack;
import com.datadog.appsec.gateway.callbacks.RequestHeadersDoneCallback;
import com.datadog.appsec.gateway.callbacks.RequestInferredClientAddressCallback;
import com.datadog.appsec.gateway.callbacks.RequestPathParamsCallback;
import com.datadog.appsec.gateway.callbacks.RequestStartedCallback;
import com.datadog.appsec.gateway.callbacks.ResponseHeaderCallback;
import com.datadog.appsec.gateway.callbacks.ResponseHeaderDoneCallback;
import com.datadog.appsec.gateway.callbacks.ResponseStartedCallback;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.SubscriptionService;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bridges the instrumentation gateway and the reactive engine. */
public class GatewayBridge {
  private static final Events<AppSecRequestContext> EVENTS = Events.get();

  private final SubscriptionService subscriptionService;
  private final EventProducerService producerService;
  private final ApiSecurityRequestSampler requestSampler;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors;

  private final SubscribersCache subscribersCache;

  public GatewayBridge(
      SubscriptionService subscriptionService,
      EventProducerService producerService,
      ApiSecurityRequestSampler requestSampler,
      List<TraceSegmentPostProcessor> traceSegmentPostProcessors) {
    this.subscriptionService = subscriptionService;
    this.producerService = producerService;
    this.requestSampler = requestSampler;
    this.traceSegmentPostProcessors = traceSegmentPostProcessors;
    this.subscribersCache = new SubscribersCache();
  }

  public void init() {
    Events<AppSecRequestContext> events = Events.get();
    Collection<datadog.trace.api.gateway.EventType<?>> additionalIGEvents =
        IGAppSecEventDependencies.additionalIGEventTypes(
            producerService.allSubscribedDataAddresses());

    subscriptionService.registerCallback(events.requestStarted(), new RequestStartedCallback());

    subscriptionService.registerCallback(
        events.requestEnded(),
        new RequestEndedCallBack(
            requestSampler, subscribersCache, producerService, traceSegmentPostProcessors));

    subscriptionService.registerCallback(EVENTS.requestHeader(), new NewRequestHeaderCallback());
    subscriptionService.registerCallback(
        EVENTS.requestHeaderDone(),
        new RequestHeadersDoneCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(
        EVENTS.requestMethodUriRaw(),
        new MethodAndRawURICallback(subscribersCache, producerService));

    subscriptionService.registerCallback(EVENTS.requestBodyStart(), new RequestBodyStartCallback());

    if (additionalIGEvents.contains(EVENTS.requestPathParams())) {
      subscriptionService.registerCallback(
          EVENTS.requestPathParams(),
          new RequestPathParamsCallback(subscribersCache, producerService));
    }

    subscriptionService.registerCallback(
        EVENTS.requestBodyDone(), new RequestBodyDoneCallback(subscribersCache, producerService));

    if (additionalIGEvents.contains(EVENTS.requestBodyProcessed())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyProcessed(),
          new RequestBodyProcessedCallback(subscribersCache, producerService));
    }

    subscriptionService.registerCallback(
        EVENTS.requestClientSocketAddress(),
        new RequestClientSocketAddressCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(
        EVENTS.requestInferredClientAddress(), new RequestInferredClientAddressCallback());

    subscriptionService.registerCallback(
        EVENTS.responseStarted(), new ResponseStartedCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(EVENTS.responseHeader(), new ResponseHeaderCallback());

    subscriptionService.registerCallback(
        EVENTS.responseHeaderDone(),
        new ResponseHeaderDoneCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(
        EVENTS.grpcServerMethod(), new GrpcServerMethodCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(
        EVENTS.grpcServerRequestMessage(),
        new GrpcServerRequestMessageCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(
        EVENTS.graphqlServerRequestMessage(),
        new GraphqlServerRequestMessageCallback(subscribersCache, producerService));

    subscriptionService.registerCallback(
        EVENTS.databaseConnection(), new DatabaseConnectionCallback());

    subscriptionService.registerCallback(
        EVENTS.databaseSqlQuery(), new DatabaseSqlQueryCallback(subscribersCache, producerService));
  }

  public void stop() {
    subscriptionService.reset();
  }

  private static class IGAppSecEventDependencies {

    private static final Map<Address<?>, Collection<datadog.trace.api.gateway.EventType<?>>>
        DATA_DEPENDENCIES = new HashMap<>(4);

    static {
      DATA_DEPENDENCIES.put(
          KnownAddresses.REQUEST_BODY_RAW, l(EVENTS.requestBodyStart(), EVENTS.requestBodyDone()));
      DATA_DEPENDENCIES.put(KnownAddresses.REQUEST_PATH_PARAMS, l(EVENTS.requestPathParams()));
      DATA_DEPENDENCIES.put(KnownAddresses.REQUEST_BODY_OBJECT, l(EVENTS.requestBodyProcessed()));
    }

    private static Collection<datadog.trace.api.gateway.EventType<?>> l(
        datadog.trace.api.gateway.EventType<?>... events) {
      return Arrays.asList(events);
    }

    static Collection<datadog.trace.api.gateway.EventType<?>> additionalIGEventTypes(
        Collection<Address<?>> addresses) {
      Set<datadog.trace.api.gateway.EventType<?>> res = new HashSet<>();
      for (Address<?> address : addresses) {
        Collection<datadog.trace.api.gateway.EventType<?>> c = DATA_DEPENDENCIES.get(address);
        if (c != null) {
          res.addAll(c);
        }
      }
      return res;
    }
  }
}
