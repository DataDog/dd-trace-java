package com.datadog.appsec.report;

import static com.datadog.appsec.util.AppSecVersion.JAVA_VERSION;
import static com.datadog.appsec.util.AppSecVersion.JAVA_VM_NAME;
import static com.datadog.appsec.util.AppSecVersion.JAVA_VM_VENDOR;

import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.contexts._definitions.AllContext;
import com.datadog.appsec.report.raw.contexts.host.Host;
import com.datadog.appsec.report.raw.contexts.http.Http100;
import com.datadog.appsec.report.raw.contexts.http.HttpRequest100;
import com.datadog.appsec.report.raw.contexts.http.HttpResponse100;
import com.datadog.appsec.report.raw.contexts.library.Library;
import com.datadog.appsec.report.raw.contexts.service.Service;
import com.datadog.appsec.report.raw.contexts.span.Span;
import com.datadog.appsec.report.raw.contexts.tags.Tags;
import com.datadog.appsec.report.raw.contexts.trace.Trace;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.datadog.appsec.util.AppSecVersion;
import datadog.trace.api.Config;
import datadog.trace.api.DDId;
import datadog.trace.api.gateway.IGSpanInfo;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventEnrichment {
  private static final String TRACER_RUNTIME_VERSION =
      JAVA_VM_VENDOR + " " + JAVA_VM_NAME + " " + JAVA_VERSION;
  private static final String OS_NAME = System.getProperty("os.name");
  private static String HOSTNAME;
  private static final Logger log = LoggerFactory.getLogger(EventEnrichment.class);

  private static String nullIfEmpty(String str) {
    return str == null || str.isEmpty() ? null : str;
  }

  public static void enrich(
      AppSecEvent100 event, IGSpanInfo spanInfo, AppSecRequestContext appSecCtx) {
    if (event.getEventId() == null) {
      event.setEventId(UUID.randomUUID().toString());
    }
    if (event.getEventType() == null) {
      event.setEventType("appsec");
    }
    if (event.getEventVersion() == null) {
      event.setEventVersion("1.0.0");
    }
    if (event.getDetectedAt() == null) {
      event.setDetectedAt(Instant.now());
    }
    if (event.getRule() == null) {
      log.warn("Event rule not available for {}", event);
    }
    if (event.getRuleMatch() == null) {
      log.warn("Event rule match not available for {}", event);
    }

    AllContext context = (AllContext) event.getContext();
    if (context == null) {
      context = new AllContext();
      event.setContext(context);
    }

    Http100 http = (Http100) context.getHttp();
    if (http == null) {
      http = new Http100();
      context.setHttp(http);
    }
    if (http.getContextVersion() == null) {
      http.setContextVersion("1.0.0");
    }

    HttpRequest100 request = http.getRequest();
    if (request == null) {
      request = new HttpRequest100();
      http.setRequest(request);
    }
    final String scheme = appSecCtx.get(KnownAddresses.REQUEST_SCHEME);
    if (request.getMethod() == null) {
      request.setMethod(appSecCtx.get(KnownAddresses.REQUEST_METHOD));
    }
    final CaseInsensitiveMap<List<String>> headersNoCookies =
        appSecCtx.get(KnownAddresses.HEADERS_NO_COOKIES);
    final String hostAndPort = extractHostAndPort(headersNoCookies);
    final String uriRaw = appSecCtx.get(KnownAddresses.REQUEST_URI_RAW);
    if (request.getUrl() == null) {
      request.setUrl(buildFullURIExclQueryString(scheme, hostAndPort, uriRaw));
    }
    if (request.getRemoteIp() == null) {
      String remoteIp = appSecCtx.get(KnownAddresses.REQUEST_CLIENT_IP);
      if (remoteIp == null) {
        remoteIp = "0.0.0.0"; // remote IP is mandatory
      }
      request.setRemoteIp(remoteIp);
    }
    if (request.getRemotePort() == null) {
      Integer remotePort = appSecCtx.get(KnownAddresses.REQUEST_CLIENT_PORT);
      if (remotePort == null) {
        remotePort = 0; // remote port is mandatory
      }
      request.setRemotePort(remotePort);
    }
    if (request.getHeaders() == null) {
      if (headersNoCookies != null) {
        request.setHeaders(headersNoCookies);
      }
    }

    HttpResponse100 response = http.getResponse();
    if (response == null) {
      response = new HttpResponse100();
      http.setResponse(response);
    }
    if (response.getStatus() == null) {
      Integer status = appSecCtx.get(KnownAddresses.RESPONSE_STATUS);
      response.setStatus(status);
    }
    if (response.getBlocked() == null) {
      response.setBlocked(appSecCtx.isBlocked());
    }

    Library library = (Library) context.getLibrary();
    if (library == null) {
      library = new Library();
      context.setLibrary(library);
    }
    if (library.getContextVersion() == null) {
      library.setContextVersion("0.1.0");
    }
    if (library.getRuntimeType() == null) {
      library.setRuntimeType("java");
    }
    if (library.getRuntimeVersion() == null) {
      library.setRuntimeVersion(TRACER_RUNTIME_VERSION);
    }
    if (library.getLibVersion() == null) {
      library.setLibVersion(AppSecVersion.VERSION);
    }

    Service service = (Service) context.getService();
    if (service == null) {
      service =
          new Service.ServiceBuilder()
              .withContextVersion("0.1.0")
              .withName(nullIfEmpty(Config.get().getServiceName()))
              .withEnvironment(nullIfEmpty(Config.get().getEnv()))
              .withVersion(nullIfEmpty(Config.get().getVersion()))
              .build();
      context.setService(service);
    }

    Span span = (Span) context.getSpan();
    if (span == null && spanInfo != null) {
      span = new Span();
      context.setSpan(span);
      span.setContextVersion("0.1.0");
      DDId spanId = spanInfo.getSpanId();
      if (spanId == null) {
        spanId = DDId.ZERO;
      }
      span.setId(spanId.toString());
    }

    Tags tags = (Tags) context.getTags();
    if (tags == null && spanInfo != null) {
      tags = new Tags();
      context.setTags(tags);
      tags.setContextVersion("0.1.0");
      Map<String, String> tagsMap = Config.get().getGlobalTags();
      if (tagsMap != null) {
        Set<String> values = new LinkedHashSet<>(tagsMap.size() * 2);
        for (Map.Entry<String, String> e : tagsMap.entrySet()) {
          if (e.getValue() != null) {
            values.add(e.getKey() + ":" + e.getValue());
          } else {
            values.add(e.getKey());
          }
        }
        tags.setValues(values);
      } else {
        tags.setValues(Collections.emptySet());
      }
    }

    Trace trace = (Trace) context.getTrace();
    if (trace == null && spanInfo != null) {
      trace = new Trace();
      context.setTrace(trace);
      trace.setContextVersion("0.1.0");
      DDId traceId = spanInfo.getTraceId();
      if (traceId == null) {
        traceId = DDId.ZERO;
      }
      trace.setId(traceId.toString());
    }

    Host host = (Host) context.getHost();
    if (host == null) {
      host = new Host();
      context.setHost(host);
    }
    if (host.getContextVersion() == null) {
      host.setContextVersion("0.1.0");
    }
    if (host.getOsType() == null) {
      host.setOsType(OS_NAME);
    }
    if (host.getHostname() == null) {
      if (HOSTNAME == null) {
        HOSTNAME = Config.getHostName();
        if (HOSTNAME == null) {
          HOSTNAME = "unknown";
        }
      }
      host.setHostname(HOSTNAME);
    }
  }

  private static String extractHostAndPort(CaseInsensitiveMap<List<String>> headers) {
    if (headers == null) {
      return "localhost";
    }
    List<String> hostHeaders = headers.get("host");
    return hostHeaders != null && hostHeaders.get(0) != null ? hostHeaders.get(0) : "localhost";
  }

  private static String buildFullURIExclQueryString(
      String scheme, String hostAndPort, String uriRaw) {
    if (scheme == null) {
      scheme = "http";
    }
    if (uriRaw == null) {
      uriRaw = "/UNKNOWN";
    }
    int posOfQuery = uriRaw.indexOf('?');
    if (posOfQuery != -1) {
      uriRaw = uriRaw.substring(0, posOfQuery);
    }

    return scheme + "://" + hostAndPort + uriRaw;
  }
}
