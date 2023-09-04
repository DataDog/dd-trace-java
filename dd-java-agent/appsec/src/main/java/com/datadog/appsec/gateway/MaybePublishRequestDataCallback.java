package com.datadog.appsec.gateway;

import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_6_10;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import datadog.trace.api.gateway.Flow;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

class MaybePublishRequestDataCallback implements Function<AppSecRequestContext, Flow<Void>> {
  private static final Map<String, List<String>> EMPTY_QUERY_PARAMS = Collections.emptyMap();
  private static final Pattern QUERY_PARAM_SPLITTER = Pattern.compile("&");
  private static final Pattern QUERY_PARAM_VALUE_SPLITTER = Pattern.compile("=");
  private final EventProducerService producerService;
  private volatile EventProducerService.DataSubscriberInfo initialReqDataSubInfo;

  public MaybePublishRequestDataCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(AppSecRequestContext ctx) {
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
            .add(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, ctx.getInferredClientIp())
            .build();

    while (true) {
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
                KnownAddresses.REQUEST_CLIENT_PORT,
                KnownAddresses.REQUEST_INFERRED_CLIENT_IP);
      }

      try {
        return producerService.publishDataEvent(initialReqDataSubInfo, ctx, bundle, false);
      } catch (ExpiredSubscriberInfoException e) {
        initialReqDataSubInfo = null;
      }
    }
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
}
