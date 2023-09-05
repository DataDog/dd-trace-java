package com.datadog.appsec.gateway;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.SubscriptionService;
import java.util.List;

/** Bridges the instrumentation gateway and the reactive engine. */
public class GatewayBridge {
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

    subscriptionService.registerCallback(events.requestStarted(), new RequestStartedCallback());

    subscriptionService.registerCallback(
        events.requestEnded(), new RequestEndedCallback(rateLimiter, traceSegmentPostProcessors));

    subscriptionService.registerCallback(events.requestHeader(), new RequestHeaderCallback());
    subscriptionService.registerCallback(
        events.requestHeaderDone(),
        new RequestHeadersDoneCallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(
        events.requestMethodUriRaw(), new MethodAndRawURICallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(events.requestBodyStart(), new RequestBodyStartCallback());

    subscriptionService.registerCallback(
        events.requestPathParams(), new RequestPathParamsCallback(producerService));

    subscriptionService.registerCallback(
        events.requestBodyDone(), new RequestBodyDoneCallback(producerService));

    subscriptionService.registerCallback(
        events.requestBodyProcessed(), new RequestBodyProcessedCallback(producerService));

    subscriptionService.registerCallback(
        events.requestClientSocketAddress(),
        new RequestClientSocketAddressCallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(
        events.requestInferredClientAddress(), new RequestInferredClientAddressCallback());

    subscriptionService.registerCallback(
        events.responseStarted(), new ResponseStartedCallback(maybePublishRequestDataCallback));

    subscriptionService.registerCallback(events.responseHeader(), new ResponseHeaderCallback());

    subscriptionService.registerCallback(
        events.responseHeaderDone(), new ResponseHeaderDoneCallback(producerService));

    subscriptionService.registerCallback(
        events.grpcServerRequestMessage(), new GrpcServerRequestMessageCallback(producerService));
  }

  public void stop() {
    subscriptionService.reset();
  }
}
