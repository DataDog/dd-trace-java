package com.datadog.appsec.gateway;

import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_6_10;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.event.data.StringKVPair;
import com.datadog.appsec.report.EventEnrichment;
import com.datadog.appsec.report.InbandReportService;
import com.datadog.appsec.report.ReportService;
import com.datadog.appsec.report.raw.events.attack.Attack010;
import datadog.trace.api.Function;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
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
  private final ReportService reportService;
  private final InbandReportService inbandReportService;

  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo initialReqDataSubInfo;
  private volatile EventProducerService.DataSubscriberInfo rawRequestBodySubInfo;

  public GatewayBridge(
      SubscriptionService subscriptionService,
      EventProducerService producerService,
      ReportService reportService,
      InbandReportService inbandReportService) {
    this.subscriptionService = subscriptionService;
    this.producerService = producerService;
    this.reportService = reportService;
    this.inbandReportService = inbandReportService;
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

          Collection<Attack010> collectedAttacks = ctx.transferCollectedAttacks();
          // If detected any attacks - mark span at appsec.event
          if (!collectedAttacks.isEmpty() && spanInfo != null) {
            spanInfo.setTag("appsec.event", true);
          }

          for (Attack010 attack : collectedAttacks) {
            EventEnrichment.enrich(attack, spanInfo, ctx);
            reportService.reportAttack(attack);
          }
          inbandReportService.reportAttacks(collectedAttacks, ctx_.getTraceSegment());

          ctx.close();
          return NoopFlow.INSTANCE;
        });

    subscriptionService.registerCallback(EVENTS.requestHeader(), new NewHeaderCallback());
    subscriptionService.registerCallback(EVENTS.requestHeaderDone(), new HeadersDoneCallback());

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

    if (additionalIGEvents.contains(EVENTS.requestBodyDone())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyDone(),
          (RequestContext<AppSecRequestContext> ctx_, StoredBodySupplier supplier) -> {
            AppSecRequestContext ctx = ctx_.getData();
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
            DataBundle bundle = MapDataBundle.of(KnownAddresses.REQUEST_BODY_RAW, bodyContent);
            return producerService.publishDataEvent(rawRequestBodySubInfo, ctx, bundle, false);
          });
    }

    subscriptionService.registerCallback(
        EVENTS.requestClientSocketAddress(),
        (ctx_, ip, port) -> {
          AppSecRequestContext ctx = ctx_.getData();
          ctx.setPeerAddress(ip);
          ctx.setPeerPort(port);
          if (isInitialRequestDataPublished(ctx)) {
            return publishInitialRequestData(ctx);
          } else {
            return NoopFlow.INSTANCE;
          }
        });

    subscriptionService.registerCallback(
        EVENTS.responseStarted(),
        (ctx_, status) -> {
          AppSecRequestContext ctx = ctx_.getData();
          ctx.setResponseStatus(status);

          MapDataBundle bundle =
              MapDataBundle.of(KnownAddresses.RESPONSE_STATUS, ctx.getResponseStatus());

          ctx.addAll(bundle);
        });
  }

  // currently unused; doesn't do anything useful
  public void stop() {
    // TODO: resetting IG not possible
    this.reportService.close();
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

  private static class NewHeaderCallback
      implements TriConsumer<RequestContext<AppSecRequestContext>, String, String> {
    @Override
    public void accept(RequestContext<AppSecRequestContext> ctx_, String name, String value) {
      AppSecRequestContext ctx = ctx_.getData();
      if (name.equalsIgnoreCase("cookie")) {
        List<StringKVPair> cookies = CookieCutter.parseCookieHeader(value);
        for (StringKVPair cookie : cookies) {
          ctx.addCookie(cookie);
        }
      } else {
        ctx.addHeader(name, value);
      }
    }
  }

  private class MethodAndRawURICallback
      implements TriFunction<
          RequestContext<AppSecRequestContext>, String, URIDataAdapter, Flow<Void>> {
    @Override
    public Flow<Void> apply(
        RequestContext<AppSecRequestContext> ctx_, String method, URIDataAdapter uri) {
      AppSecRequestContext ctx = ctx_.getData();
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
      if (isInitialRequestDataPublished(ctx)) {
        return publishInitialRequestData(ctx);
      } else {
        return NoopFlow.INSTANCE;
      }
    }
  }

  private class HeadersDoneCallback
      implements Function<RequestContext<AppSecRequestContext>, Flow<Void>> {
    public Flow<Void> apply(RequestContext<AppSecRequestContext> ctx_) {
      AppSecRequestContext ctx = ctx_.getData();

      ctx.finishHeaders();

      if (isInitialRequestDataPublished(ctx)) {
        return publishInitialRequestData(ctx);
      } else {
        return NoopFlow.INSTANCE;
      }
    }
  }

  private static boolean isInitialRequestDataPublished(AppSecRequestContext ctx) {
    return ctx.getSavedRawURI() != null && ctx.isFinishedHeaders() && ctx.getPeerAddress() != null;
  }

  private Flow<Void> publishInitialRequestData(AppSecRequestContext ctx) {
    String savedRawURI = ctx.getSavedRawURI();
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
            .add(KnownAddresses.HEADERS_NO_COOKIES, ctx.getCollectedHeaders())
            .add(KnownAddresses.REQUEST_COOKIES, ctx.getCollectedCookies())
            .add(KnownAddresses.REQUEST_SCHEME, scheme)
            .add(KnownAddresses.REQUEST_METHOD, ctx.getMethod())
            .add(KnownAddresses.REQUEST_URI_RAW, savedRawURI)
            .add(KnownAddresses.REQUEST_QUERY, queryParams)
            .add(KnownAddresses.REQUEST_CLIENT_IP, ctx.getPeerAddress())
            .add(KnownAddresses.REQUEST_CLIENT_PORT, ctx.getPeerPort())
            .build();

    return producerService.publishDataEvent(initialReqDataSubInfo, ctx, bundle, false);
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
      List<String> strings = result.get(key);
      if (strings == null) {
        strings = new ArrayList<>(1);
        result.put(key, strings);
      }
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
        DATA_DEPENDENCIES = new HashMap<>(2);

    static {
      EVENT_DEPENDENCIES.put(EventType.REQUEST_BODY_START, l(EVENTS.requestBodyStart()));
      EVENT_DEPENDENCIES.put(EventType.REQUEST_BODY_END, l(EVENTS.requestBodyDone()));

      DATA_DEPENDENCIES.put(
          KnownAddresses.REQUEST_BODY_RAW, l(EVENTS.requestBodyStart(), EVENTS.requestBodyDone()));
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
