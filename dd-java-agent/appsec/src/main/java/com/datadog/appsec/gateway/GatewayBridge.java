package com.datadog.appsec.gateway;

import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_6_10;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.*;
import com.datadog.appsec.report.AppSecEventWrapper;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import datadog.trace.api.DDTags;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.function.Function;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.util.Strings;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges the instrumentation gateway and the reactive engine. */
public class GatewayBridge {
  private static final Events<AppSecRequestContext> EVENTS = Events.get();

  private static final Logger log = LoggerFactory.getLogger(GatewayBridge.class);

  private static final Pattern QUERY_PARAM_VALUE_SPLITTER = Pattern.compile("=");
  private static final Pattern QUERY_PARAM_SPLITTER = Pattern.compile("&");
  private static final Map<String, List<String>> EMPTY_QUERY_PARAMS = Collections.emptyMap();

  private final SubscriptionService subscriptionService;
  private final EventProducerService producerService;
  private final RateLimiter rateLimiter;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors;

  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo initialReqDataSubInfo;
  private volatile EventProducerService.DataSubscriberInfo rawRequestBodySubInfo;
  private volatile EventProducerService.DataSubscriberInfo requestBodySubInfo;
  private volatile EventProducerService.DataSubscriberInfo pathParamsSubInfo;
  private volatile EventProducerService.DataSubscriberInfo respDataSubInfo;
  private volatile EventProducerService.DataSubscriberInfo grpcServerRequestMsgSubInfo;

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

    subscriptionService.registerCallback(
        events.requestStarted(),
        () -> {
          RequestContextSupplier requestContextSupplier = new RequestContextSupplier();
          AppSecRequestContext ctx = requestContextSupplier.getResult();
          producerService.publishEvent(ctx, EventType.REQUEST_START);

          return requestContextSupplier;
        });

    subscriptionService.registerCallback(
        events.requestEnded(),
        (RequestContext<AppSecRequestContext> ctx_, IGSpanInfo spanInfo) -> {
          AppSecRequestContext ctx = ctx_.getData();
          producerService.publishEvent(ctx, EventType.REQUEST_END);

          TraceSegment traceSeg = ctx_.getTraceSegment();

          // AppSec report metric and events for web span only
          if (traceSeg != null) {
            traceSeg.setTagTop("_dd.appsec.enabled", 1);
            traceSeg.setTagTop("_dd.runtime_family", "jvm");

            Collection<AppSecEvent100> collectedEvents = ctx.transferCollectedEvents();

            for (TraceSegmentPostProcessor pp : this.traceSegmentPostProcessors) {
              pp.processTraceSegment(traceSeg, ctx, collectedEvents);
            }

            // If detected any events - mark span at appsec.event
            if (!collectedEvents.isEmpty() && (rateLimiter == null || !rateLimiter.isThrottled())) {
              // Keep event related span, because it could be ignored in case of
              // reduced datadog sampling rate.
              traceSeg.setTagTop(DDTags.MANUAL_KEEP, true);
              traceSeg.setTagTop("appsec.event", true);
              traceSeg.setTagTop("network.client.ip", ctx.getPeerAddress());

              Map<String, List<String>> requestHeaders = ctx.getRequestHeaders();
              Map<String, List<String>> responseHeaders = ctx.getResponseHeaders();
              // Reflect client_ip as actor.ip for backward compatibility
              Object clientIp = spanInfo.getTags().get(Tags.HTTP_CLIENT_IP);
              if (clientIp != null) {
                traceSeg.setTagTop("actor.ip", clientIp);
              }

              // Report AppSec events via "_dd.appsec.json" tag
              AppSecEventWrapper wrapper = new AppSecEventWrapper(collectedEvents);
              traceSeg.setDataTop("appsec", wrapper);

              // Report collected request and response headers based on allow list
              if (requestHeaders != null) {
                requestHeaders.forEach(
                    (name, value) -> {
                      if (AppSecRequestContext.HEADERS_ALLOW_LIST.contains(name)) {
                        String v = Strings.join(",", value);
                        if (!v.isEmpty()) {
                          traceSeg.setTagTop("http.request.headers." + name, v);
                        }
                      }
                    });
              }
              if (responseHeaders != null) {
                responseHeaders.forEach(
                    (name, value) -> {
                      if (AppSecRequestContext.HEADERS_ALLOW_LIST.contains(name)) {
                        String v = String.join(",", value);
                        if (!v.isEmpty()) {
                          traceSeg.setTagTop("http.response.headers." + name, v);
                        }
                      }
                    });
              }
            }
          }

          ctx.close();
          return NoopFlow.INSTANCE;
        });

    subscriptionService.registerCallback(EVENTS.requestHeader(), new NewRequestHeaderCallback());
    subscriptionService.registerCallback(
        EVENTS.requestHeaderDone(), new RequestHeadersDoneCallback());

    subscriptionService.registerCallback(
        EVENTS.requestMethodUriRaw(), new MethodAndRawURICallback());

    if (additionalIGEvents.contains(EVENTS.requestBodyStart())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyStart(),
          (RequestContext<AppSecRequestContext> ctx_, StoredBodySupplier supplier) -> {
            AppSecRequestContext ctx = ctx_.getData();
            ctx.setStoredRequestBodySupplier(supplier);
            producerService.publishEvent(ctx, EventType.REQUEST_BODY_START);
            return null;
          });
    }

    if (additionalIGEvents.contains(EVENTS.requestPathParams())) {
      subscriptionService.registerCallback(
          EVENTS.requestPathParams(),
          (ctx_, data) -> {
            AppSecRequestContext ctx = ctx_.getData();
            if (ctx.isPathParamsPublished()) {
              log.debug("Second or subsequent publication of request params");
              return NoopFlow.INSTANCE;
            }

            if (pathParamsSubInfo == null) {
              pathParamsSubInfo =
                  producerService.getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS);
            }
            DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_PATH_PARAMS, data);
            return producerService.publishDataEvent(pathParamsSubInfo, ctx, bundle, false);
          });
    }

    if (additionalIGEvents.contains(EVENTS.requestBodyDone())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyDone(),
          (RequestContext<AppSecRequestContext> ctx_, StoredBodySupplier supplier) -> {
            AppSecRequestContext ctx = ctx_.getData();

            if (ctx.isRawReqBodyPublished()) {
              return NoopFlow.INSTANCE;
            }
            ctx.setRawReqBodyPublished(true);

            producerService.publishEvent(ctx, EventType.REQUEST_BODY_END);

            if (rawRequestBodySubInfo == null) {
              rawRequestBodySubInfo =
                  producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW);
            }
            if (rawRequestBodySubInfo.isEmpty()) {
              return NoopFlow.INSTANCE;
            }

            CharSequence bodyContent = supplier.get();
            if (bodyContent == null || bodyContent.length() == 0) {
              return NoopFlow.INSTANCE;
            }
            DataBundle bundle =
                new SingletonDataBundle<>(KnownAddresses.REQUEST_BODY_RAW, bodyContent);
            return producerService.publishDataEvent(rawRequestBodySubInfo, ctx, bundle, false);
          });
    }

    if (additionalIGEvents.contains(EVENTS.requestBodyProcessed())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyProcessed(),
          (RequestContext<AppSecRequestContext> ctx_, Object obj) -> {
            AppSecRequestContext ctx = ctx_.getData();
            if (ctx.isConvertedReqBodyPublished()) {
              log.debug(
                  "Request body already published; will ignore new value of type {}",
                  obj.getClass());
              return NoopFlow.INSTANCE;
            }
            ctx.setConvertedReqBodyPublished(true);

            if (requestBodySubInfo == null) {
              requestBodySubInfo =
                  producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_OBJECT);
            }
            if (requestBodySubInfo.isEmpty()) {
              return NoopFlow.INSTANCE;
            }
            DataBundle bundle =
                new SingletonDataBundle<>(
                    KnownAddresses.REQUEST_BODY_OBJECT, ObjectIntrospection.convert(obj));
            return producerService.publishDataEvent(requestBodySubInfo, ctx, bundle, false);
          });
    }

    subscriptionService.registerCallback(
        EVENTS.requestClientSocketAddress(),
        (ctx_, ip, port) -> {
          AppSecRequestContext ctx = ctx_.getData();
          if (ctx.isReqDataPublished()) {
            return NoopFlow.INSTANCE;
          }
          ctx.setPeerAddress(ip);
          ctx.setPeerPort(port);
          return maybePublishRequestData(ctx);
        });

    subscriptionService.registerCallback(
        EVENTS.responseStarted(),
        (ctx_, status) -> {
          AppSecRequestContext ctx = ctx_.getData();
          if (ctx.isRespDataPublished()) {
            return NoopFlow.INSTANCE;
          }
          ctx.setResponseStatus(status);
          return maybePublishResponseData(ctx);
        });

    subscriptionService.registerCallback(
        EVENTS.responseHeader(),
        (ctx, name, value) -> ctx.getData().addResponseHeader(name, value));
    subscriptionService.registerCallback(
        EVENTS.responseHeaderDone(),
        ctx_ -> {
          AppSecRequestContext ctx = ctx_.getData();
          if (ctx.isRespDataPublished()) {
            return NoopFlow.INSTANCE;
          }
          ctx.finishResponseHeaders();
          return maybePublishResponseData(ctx);
        });

    subscriptionService.registerCallback(
        EVENTS.grpcServerRequestMessage(),
        (ctx_, obj) -> {
          AppSecRequestContext ctx = ctx_.getData();
          if (grpcServerRequestMsgSubInfo == null) {
            grpcServerRequestMsgSubInfo =
                producerService.getDataSubscribers(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE);
          }
          if (grpcServerRequestMsgSubInfo.isEmpty()) {
            return Flow.ResultFlow.empty();
          }
          Object convObj = ObjectIntrospection.convert(obj);
          DataBundle bundle =
              new SingletonDataBundle<>(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE, convObj);
          return producerService.publishDataEvent(grpcServerRequestMsgSubInfo, ctx, bundle, true);
        });
  }

  // currently unused; doesn't do anything useful
  public void stop() {
    // TODO: resetting IG not possible
  }

  private static class RequestContextSupplier implements Flow<AppSecRequestContext> {
    private final AppSecRequestContext appSecRequestContext = new AppSecRequestContext();

    @Override
    public Action getAction() {
      return Action.Noop.INSTANCE;
    }

    @Override
    public AppSecRequestContext getResult() {
      return appSecRequestContext;
    }
  }

  private static class NewRequestHeaderCallback
      implements TriConsumer<RequestContext<AppSecRequestContext>, String, String> {
    @Override
    public void accept(RequestContext<AppSecRequestContext> ctx_, String name, String value) {
      AppSecRequestContext ctx = ctx_.getData();
      if (name.equalsIgnoreCase("cookie")) {
        Map<String, List<String>> cookies = CookieCutter.parseCookieHeader(value);
        ctx.addCookies(cookies);
      } else {
        ctx.addRequestHeader(name, value);
      }
    }
  }

  private class RequestHeadersDoneCallback
      implements Function<RequestContext<AppSecRequestContext>, Flow<Void>> {
    public Flow<Void> apply(RequestContext<AppSecRequestContext> ctx_) {
      AppSecRequestContext ctx = ctx_.getData();
      if (ctx.isReqDataPublished()) {
        return NoopFlow.INSTANCE;
      }
      ctx.finishRequestHeaders();
      return maybePublishRequestData(ctx);
    }
  }

  private class MethodAndRawURICallback
      implements TriFunction<
          RequestContext<AppSecRequestContext>, String, URIDataAdapter, Flow<Void>> {
    @Override
    public Flow<Void> apply(
        RequestContext<AppSecRequestContext> ctx_, String method, URIDataAdapter uri) {
      AppSecRequestContext ctx = ctx_.getData();
      if (ctx.isReqDataPublished()) {
        log.debug(
            "Request method and URI already published; will ignore new values {}, {}", method, uri);
        return NoopFlow.INSTANCE;
      }
      ctx.setMethod(method);
      ctx.setScheme(uri.scheme());
      if (uri.supportsRaw()) {
        ctx.setRawURI(uri.raw());
      } else {
        try {
          URI encodedUri = new URI(null, null, uri.path(), uri.query(), null);
          String q = encodedUri.getRawQuery();
          StringBuilder encoded = new StringBuilder();
          encoded.append(encodedUri.getRawPath());
          if (null != q && !q.isEmpty()) {
            encoded.append('?').append(q);
          }
          ctx.setRawURI(encoded.toString());
        } catch (URISyntaxException e) {
          log.debug("Failed to encode URI '{}{}'", uri.path(), uri.query());
        }
      }
      return maybePublishRequestData(ctx);
    }
  }

  private Flow<Void> maybePublishRequestData(AppSecRequestContext ctx) {
    String savedRawURI = ctx.getSavedRawURI();

    if (savedRawURI == null || !ctx.isFinishedRequestHeaders() || ctx.getPeerAddress() == null) {
      return NoopFlow.INSTANCE;
    }

    Map<String, List<String>> queryParams = EMPTY_QUERY_PARAMS;
    int i = savedRawURI.indexOf("?");
    if (i != -1) {
      String qs = savedRawURI.substring(i + 1);
      // ideally we'd have the query string as parsed by the server
      // or at the very least the encoding used by the server
      queryParams = parseQueryStringParams(qs, StandardCharsets.UTF_8);
    }

    String scheme = ctx.getScheme();
    if (scheme == null) {
      scheme = "http";
    }

    ctx.setReqDataPublished(true);

    if (initialReqDataSubInfo == null) {
      initialReqDataSubInfo =
          producerService.getDataSubscribers(
              KnownAddresses.HEADERS_NO_COOKIES,
              KnownAddresses.REQUEST_COOKIES,
              KnownAddresses.REQUEST_SCHEME,
              KnownAddresses.REQUEST_METHOD,
              KnownAddresses.REQUEST_URI_RAW,
              KnownAddresses.REQUEST_QUERY,
              KnownAddresses.REQUEST_CLIENT_IP,
              KnownAddresses.REQUEST_CLIENT_PORT);
    }
    MapDataBundle bundle =
        new MapDataBundle.Builder(CAPACITY_6_10)
            .add(KnownAddresses.HEADERS_NO_COOKIES, ctx.getRequestHeaders())
            .add(KnownAddresses.REQUEST_COOKIES, ctx.getCookies())
            .add(KnownAddresses.REQUEST_SCHEME, scheme)
            .add(KnownAddresses.REQUEST_METHOD, ctx.getMethod())
            .add(KnownAddresses.REQUEST_URI_RAW, savedRawURI)
            .add(KnownAddresses.REQUEST_QUERY, queryParams)
            .add(KnownAddresses.REQUEST_CLIENT_IP, ctx.getPeerAddress())
            .add(KnownAddresses.REQUEST_CLIENT_PORT, ctx.getPeerPort())
            .build();

    return producerService.publishDataEvent(initialReqDataSubInfo, ctx, bundle, false);
  }

  private Flow<Void> maybePublishResponseData(AppSecRequestContext ctx) {

    int status = ctx.getResponseStatus();

    if (status == 0 || !ctx.isFinishedResponseHeaders()) {
      return NoopFlow.INSTANCE;
    }

    ctx.setRespDataPublished(true);

    MapDataBundle bundle =
        MapDataBundle.of(
            KnownAddresses.RESPONSE_STATUS, String.valueOf(ctx.getResponseStatus()),
            KnownAddresses.RESPONSE_HEADERS_NO_COOKIES, ctx.getResponseHeaders());

    if (respDataSubInfo == null) {
      respDataSubInfo =
          producerService.getDataSubscribers(
              KnownAddresses.RESPONSE_STATUS, KnownAddresses.RESPONSE_HEADERS_NO_COOKIES);
    }

    return producerService.publishDataEvent(respDataSubInfo, ctx, bundle, false);
  }

  private static Map<String, List<String>> parseQueryStringParams(
      String queryString, Charset uriEncoding) {
    if (queryString == null) {
      return Collections.emptyMap();
    }

    Map<String, List<String>> result = new HashMap<>();

    String[] keyValues = QUERY_PARAM_SPLITTER.split(queryString);

    for (String keyValue : keyValues) {
      String[] kv = QUERY_PARAM_VALUE_SPLITTER.split(keyValue, 2);
      String value = kv.length > 1 ? urlDecode(kv[1], uriEncoding, true) : "";
      String key = urlDecode(kv[0], uriEncoding, true);
      List<String> strings = result.computeIfAbsent(key, k -> new ArrayList<>(1));
      strings.add(value);
    }

    return result;
  }

  private static String urlDecode(String str, Charset charset, boolean queryString) {
    return decodeString(str, charset, queryString, Integer.MAX_VALUE);
  }

  private static String decodeString(String str, Charset charset, boolean queryString, int limit) {
    byte[] bytes = str.getBytes(charset);
    int j = 0;
    for (int i = 0; i < bytes.length && j < limit; i++, j++) {
      int b = bytes[i];
      if (b == 0x25 /* % */) {
        if (i + 2 < bytes.length) {
          int val = byteToDigit(bytes[i + 2]);
          if (val >= 0) {
            val += 16 * byteToDigit(bytes[i + 1]);
            if (val >= 0) {
              i += 2;
              bytes[j] = (byte) val;
              continue;
            }
          }
        }
      } else if (b == 0x2b /* + */ && queryString) {
        bytes[j] = ' ';
        continue;
      }
      bytes[j] = (byte) b;
    }

    return new String(bytes, 0, j, charset);
  }

  private static int byteToDigit(byte b) {
    if (b >= 0x30 /* 0 */ && b <= 0x39 /* 9 */) {
      return b - 0x30;
    }
    if (b >= 0x41 /* A */ && b <= 0x46 /* F */) {
      return 10 + (b - 0x41);
    }
    if (b >= 0x61 /* a */ && b <= 0x66 /* f */) {
      return 10 + (b - 0x61);
    }
    return -1;
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
