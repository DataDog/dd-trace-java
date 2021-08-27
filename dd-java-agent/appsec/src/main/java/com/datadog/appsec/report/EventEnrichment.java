package com.datadog.appsec.report;

import static com.datadog.appsec.util.AppSecVersion.JAVA_VERSION;
import static com.datadog.appsec.util.AppSecVersion.JAVA_VM_NAME;
import static com.datadog.appsec.util.AppSecVersion.JAVA_VM_VENDOR;

import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.report.raw.contexts._definitions.AllContext;
import com.datadog.appsec.report.raw.contexts.host.Host010;
import com.datadog.appsec.report.raw.contexts.http.Http010;
import com.datadog.appsec.report.raw.contexts.http.HttpHeaders;
import com.datadog.appsec.report.raw.contexts.http.HttpRequest;
import com.datadog.appsec.report.raw.contexts.service_stack.Service;
import com.datadog.appsec.report.raw.contexts.service_stack.ServiceStack010;
import com.datadog.appsec.report.raw.contexts.span.Span010;
import com.datadog.appsec.report.raw.contexts.tags.Tags010;
import com.datadog.appsec.report.raw.contexts.trace.Trace010;
import com.datadog.appsec.report.raw.contexts.tracer.Tracer010;
import com.datadog.appsec.report.raw.events.attack.Attack010;
import com.datadog.appsec.util.AppSecVersion;
import datadog.trace.api.Config;
import datadog.trace.api.DDId;
import datadog.trace.api.gateway.IGSpanInfo;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventEnrichment {
  private static final String TRACER_RUNTIME_VERSION =
      JAVA_VM_VENDOR + " " + JAVA_VM_NAME + " " + JAVA_VERSION;
  private static final String OS_NAME = System.getProperty("os.name");
  private static String HOSTNAME;
  private static final Logger log = LoggerFactory.getLogger(EventEnrichment.class);

  public static void enrich(Attack010 attack, IGSpanInfo spanInfo, DataBundle appSecCtx) {
    if (attack.getEventId() == null) {
      attack.setEventId(UUID.randomUUID().toString());
    }
    if (attack.getEventType() == null) {
      attack.setEventType("appsec.threat.attack");
    }
    if (attack.getEventVersion() == null) {
      attack.setEventVersion("0.1.0");
    }
    if (attack.getDetectedAt() == null) {
      attack.setDetectedAt(Instant.now());
    }
    if (attack.getType() == null) {
      log.warn("Event type not available for {}", attack);
    }
    if (attack.getBlocked() == null) {
      log.warn("Event block flag not available for {}", attack);
    }
    if (attack.getRule() == null) {
      log.warn("Event rule not available for {}", attack);
    }
    if (attack.getRuleMatch() == null) {
      log.warn("Event rule match not available for {}", attack);
    }

    AllContext eventCtx = (AllContext) attack.getContext();
    if (eventCtx == null) {
      eventCtx = new AllContext();
      attack.setContext(eventCtx);
    }

    Http010 http = (Http010) eventCtx.getHttp();
    if (http == null) {
      http = new Http010();
      eventCtx.setHttp(http);
    }
    if (http.getContextVersion() == null) {
      http.setContextVersion("0.1.0");
    }

    HttpRequest request = http.getRequest();
    if (request == null) {
      request = new HttpRequest();
      http.setRequest(request);
    }
    final String scheme = appSecCtx.get(KnownAddresses.REQUEST_SCHEME);
    if (request.getScheme() == null) {
      request.setScheme(scheme);
    }
    if (request.getMethod() == null) {
      request.setMethod(appSecCtx.get(KnownAddresses.REQUEST_METHOD));
    }
    final CaseInsensitiveMap<List<String>> headersNoCookies =
        appSecCtx.get(KnownAddresses.HEADERS_NO_COOKIES);
    final String hostAndPort = extractHostAndPort(headersNoCookies);
    final int hostPosOfColon = hostAndPort.indexOf(":");
    if (request.getHost() == null) {
      if (hostPosOfColon == -1) {
        request.setHost(hostAndPort);
      } else {
        request.setHost(hostAndPort.substring(0, hostPosOfColon));
      }
    }
    final String uriRaw = appSecCtx.get(KnownAddresses.REQUEST_URI_RAW);
    if (request.getUrl() == null) {
      request.setUrl(buildFullURIExclQueryString(scheme, hostAndPort, uriRaw));
    }
    if (request.getPort() == null) {
      int port = -1;
      if (hostPosOfColon != -1) {
        try {
          port = Integer.parseInt(hostAndPort.substring(hostPosOfColon + 1));
        } catch (RuntimeException e) {
          log.info("Could not parse port");
          if (port <= 0 || port > 65535) {
            log.info("Invalid port: {}", port);
          }
        }
      }
      if (port == -1) {
        port = "http".equalsIgnoreCase(scheme) ? 80 : 443;
      }
      request.setPort(port);
    }
    if (request.getPath() == null) {
      String s = uriRaw;
      if (s == null) {
        log.info("Request path not available");
        s = "/UNKNOWN";
      }
      if (s.contains("?")) {
        s = s.substring(0, s.indexOf("?"));
      }
      request.setPath(s);
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
        request.setHeaders(new HttpHeaders(headersNoCookies));
      }
    }

    Tracer010 tracer = (Tracer010) eventCtx.getTracer();
    if (tracer == null) {
      tracer = new Tracer010();
      eventCtx.setTracer(tracer);
    }
    if (tracer.getContextVersion() == null) {
      tracer.setContextVersion("0.1.0");
    }
    if (tracer.getRuntimeType() == null) {
      tracer.setRuntimeType("java");
    }
    if (tracer.getRuntimeVersion() == null) {
      tracer.setRuntimeVersion(TRACER_RUNTIME_VERSION);
    }
    if (tracer.getLibVersion() == null) {
      tracer.setLibVersion(AppSecVersion.VERSION);
    }

    Service service = (Service) eventCtx.getService();
    if (service == null) {
      service =
          new Service.ServiceBuilder()
              .withProperty("context_version", "0.1.0")
              .withProperty("name", Config.get().getServiceName())
              .withProperty("environment", Config.get().getEnv())
              .withProperty("version", Config.get().getVersion())
              .build();
      eventCtx.setService(service);
    }

    ServiceStack010 serviceStack = (ServiceStack010) eventCtx.getServiceStack();
    if (serviceStack == null) {
      serviceStack = new ServiceStack010();
      eventCtx.setServiceStack(serviceStack);
    }
    if (serviceStack.getContextVersion() == null) {
      serviceStack.setContextVersion("0.1.0");
    }
    List<Service> services = serviceStack.getServices();
    if (services == null || services.isEmpty()) {
      serviceStack.setServices(Collections.singletonList(service));
    }

    Span010 span = (Span010) eventCtx.getSpan();
    if (span == null && spanInfo != null) {
      span = new Span010();
      eventCtx.setSpan(span);
      span.setContextVersion("0.1.0");
      DDId spanId = spanInfo.getSpanId();
      if (spanId == null) {
        spanId = DDId.ZERO;
      }
      span.setId(spanId.toHexStringOrOriginal());
    }

    Tags010 tags = (Tags010) eventCtx.getTags();
    if (tags == null && spanInfo != null) {
      tags = new Tags010();
      eventCtx.setTags(tags);
      tags.setContextVersion("0.1.0");
      Map<String, Object> tagsMap = spanInfo.getTags();
      if (tagsMap != null) {
        Set<String> values = new LinkedHashSet<>(tagsMap.size() * 2);
        for (Map.Entry<String, Object> e : tagsMap.entrySet()) {
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

    Trace010 trace = (Trace010) eventCtx.getTrace();
    if (trace == null && spanInfo != null) {
      trace = new Trace010();
      eventCtx.setTrace(trace);
      trace.setContextVersion("0.1.0");
      DDId traceId = spanInfo.getTraceId();
      if (traceId == null) {
        traceId = DDId.ZERO;
      }
      trace.setId(traceId.toHexStringOrOriginal());
    }

    Host010 host = (Host010) eventCtx.getHost();
    if (host == null) {
      host = new Host010();
      eventCtx.setHost(host);
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
