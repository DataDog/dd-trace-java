package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.event.data.StringKVPair;
import datadog.trace.api.Function;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.SubscriptionService;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges the instrumentation gateway and the reactive engine. */
public class GatewayBridge {
  private static final Logger LOG = LoggerFactory.getLogger(GatewayBridge.class);
  private final SubscriptionService subscriptionService;
  private final EventProducerService producerService;

  public GatewayBridge(
      SubscriptionService subscriptionService, EventProducerService producerService) {
    this.subscriptionService = subscriptionService;
    this.producerService = producerService;
  }

  public void init() {
    // TODO: awkward use of flow and double callback
    subscriptionService.registerCallback(
        Events.REQUEST_STARTED,
        () -> {
          RequestContextSupplier requestContextSupplier = new RequestContextSupplier();
          AppSecRequestContext ctx = requestContextSupplier.getResult();
          Flow flow = producerService.publishEvent(ctx, EventType.REQUEST_START);
          if (flow.getAction().isBlocking()) {
            LOG.warn("Blocking not allowed on REQUEST_STARTED event");
          }
          return requestContextSupplier;
        });

    subscriptionService.registerCallback(
        Events.REQUEST_ENDED,
        (AppSecRequestContext ctx) -> {
          Flow flow = producerService.publishEvent(ctx, EventType.REQUEST_END);

          ctx.close();
          return flow;
        });

    subscriptionService.registerCallback(Events.REQUEST_HEADER, new NewHeaderCallback());
    subscriptionService.registerCallback(
        Events.REQUEST_HEADER_DONE, new HeadersDoneCallback(producerService));

    subscriptionService.registerCallback(
        Events.REQUEST_URI_RAW,
        (AppSecRequestContext ctx, String s) -> {
          ctx.setRawURI(s);
          return NoopFlow.INSTANCE;
        });
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
      implements TriConsumer<AppSecRequestContext, String, String> {
    @Override
    public void accept(AppSecRequestContext ctx, String name, String value) {
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

  private static class HeadersDoneCallback implements Function<AppSecRequestContext, Flow<Void>> {
    private static final Pattern QUERY_PARAM_VALUE_SPLITTER = Pattern.compile("=");
    private static final Pattern QUERY_PARAM_SPLITTER = Pattern.compile("&");
    private static final Map<String, List<String>> EMPTY_QUERY_PARAMS = Collections.emptyMap();

    private final EventProducerService producerService;

    private HeadersDoneCallback(EventProducerService producerService) {
      this.producerService = producerService;
    }

    public Flow<Void> apply(AppSecRequestContext ctx) {
      String savedRawURI = ctx.getSavedRawURI();
      Map<String, List<String>> queryParams = EMPTY_QUERY_PARAMS;
      if (savedRawURI == null) {
        LOG.info("No saved RAW URI");
        savedRawURI = "/";
      }
      int i = savedRawURI.indexOf("?");
      if (i != -1) {
        String qs = savedRawURI.substring(i + 1);
        // ideally we'd have the query string as parsed by the server
        // or at the very least the encoding used by the server
        queryParams = parseQueryStringParams(qs, StandardCharsets.UTF_8);
      }

      EventProducerService.DataSubscriberInfo dataSubscribers =
          producerService.getDataSubscribers(
              ctx,
              KnownAddresses.HEADERS_NO_COOKIES,
              KnownAddresses.REQUEST_COOKIES,
              KnownAddresses.REQUEST_URI_RAW,
              KnownAddresses.REQUEST_QUERY);

      MapDataBundle bundle =
          MapDataBundle.of(
              KnownAddresses.HEADERS_NO_COOKIES,
              ctx.getCollectedHeaders(),
              KnownAddresses.REQUEST_COOKIES,
              ctx.getCollectedCookies(),
              KnownAddresses.REQUEST_URI_RAW,
              savedRawURI,
              KnownAddresses.REQUEST_QUERY,
              queryParams);

      ctx.finishHeaders();

      return producerService.publishDataEvent(dataSubscribers, ctx, bundle, false);
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

    private static String decodeString(
        String str, Charset charset, boolean queryString, int limit) {
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
  }
}
