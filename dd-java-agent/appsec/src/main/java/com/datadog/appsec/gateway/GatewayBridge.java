package com.datadog.appsec.gateway;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.SubscriptionService;
import java.util.List;

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

    subscriptionService.registerCallback(
        EVENTS.requestBodyStart(), new RequestBodyStartCallback(producerService));

    subscriptionService.registerCallback(
        EVENTS.requestPathParams(), new RequestPathParamsCallback(producerService));

    subscriptionService.registerCallback(
        EVENTS.requestBodyDone(), new RequestBodyDoneCallback(producerService));

    subscriptionService.registerCallback(
        EVENTS.requestBodyProcessed(), new RequestBodyProcessedCallback(producerService));

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
}
