package com.datadog.appsec.gateway;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.KnownAddresses;
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
  private final RateLimiter rateLimiter;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors;

  public GatewayBridge(
      SubscriptionService subscriptionService,
      EventProducerService producerService,
      RateLimiter rateLimiter,
      List<TraceSegmentPostProcessor> traceSegmentPostProcessors) {
    this.subscriptionService = subscriptionService;
    this.producerService = producerService;
    this.rateLimiter = rateLimiter;
    this.traceSegmentPostProcessors = traceSegmentPostProcessors;
  }

  public void init() {
    Events<AppSecRequestContext> events = Events.get();
    Collection<datadog.trace.api.gateway.EventType<?>> additionalIGEvents =
        IGAppSecEventDependencies.additionalIGEventTypes(
            producerService.allSubscribedEvents(), producerService.allSubscribedDataAddresses());

    final MaybePublishRequestDataCallback maybePublishRequestDataCallback =
        new MaybePublishRequestDataCallback(producerService);

    subscriptionService.registerCallback(
        events.requestStarted(), new RequestStartedCallback(producerService));

    subscriptionService.registerCallback(
        events.requestEnded(),
        new RequestEndedCallback(producerService, rateLimiter, traceSegmentPostProcessors));

    subscriptionService.registerCallback(EVENTS.requestHeader(), new NewRequestHeaderCallback());
    subscriptionService.registerCallback(
        EVENTS.requestHeaderDone(),
        new RequestHeadersDoneCallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(
        EVENTS.requestMethodUriRaw(), new MethodAndRawURICallback(maybePublishRequestDataCallback));

    if (additionalIGEvents.contains(EVENTS.requestBodyStart())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyStart(), new RequestBodyStartCallback(producerService));
    }

    if (additionalIGEvents.contains(EVENTS.requestPathParams())) {
      subscriptionService.registerCallback(
          EVENTS.requestPathParams(), new RequestPathParamsCallback(producerService));
    }

    if (additionalIGEvents.contains(EVENTS.requestBodyDone())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyDone(), new RequestBodyDoneCallback(producerService));
    }

    if (additionalIGEvents.contains(EVENTS.requestBodyProcessed())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyProcessed(), new RequestBodyProcessedCallback(producerService));
    }

    subscriptionService.registerCallback(
        EVENTS.requestClientSocketAddress(),
        new RequestClientSocketAddressCallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(
        EVENTS.requestInferredClientAddress(), new RequestInferredClientAddressCallback());

    subscriptionService.registerCallback(
        EVENTS.responseStarted(), new ResponseStartedCallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(EVENTS.responseHeader(), new ResponseHeaderCallback());

    subscriptionService.registerCallback(
        EVENTS.responseHeaderDone(), new ResponseHeaderDoneCallback(producerService));

    subscriptionService.registerCallback(
        EVENTS.grpcServerRequestMessage(), new GrpcServerRequestMessageCallback(producerService));
  }

  public void stop() {
    subscriptionService.reset();
  }

  private static class IGAppSecEventDependencies {
    private static final Map<EventType, Collection<datadog.trace.api.gateway.EventType<?>>>
        EVENT_DEPENDENCIES = new HashMap<>(3); // ceil(2 / .75)

    private static final Map<Address<?>, Collection<datadog.trace.api.gateway.EventType<?>>>
        DATA_DEPENDENCIES = new HashMap<>(4);

    static {
      EVENT_DEPENDENCIES.put(EventType.REQUEST_BODY_START, l(EVENTS.requestBodyStart()));
      EVENT_DEPENDENCIES.put(EventType.REQUEST_BODY_END, l(EVENTS.requestBodyDone()));

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
        Collection<EventType> eventTypes, Collection<Address<?>> addresses) {
      Set<datadog.trace.api.gateway.EventType<?>> res = new HashSet<>();
      for (EventType eventType : eventTypes) {
        Collection<datadog.trace.api.gateway.EventType<?>> c = EVENT_DEPENDENCIES.get(eventType);
        if (c != null) {
          res.addAll(c);
        }
      }
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
