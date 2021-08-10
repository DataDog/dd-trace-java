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
import com.datadog.appsec.report.raw.contexts.tracer.Tracer010;
import com.datadog.appsec.report.raw.events.attack.Attack010;
import com.datadog.appsec.util.AppSecVersion;
import datadog.trace.api.Config;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventEnrichment {
  private static final String TRACER_RUNTIME_VERSION =
      JAVA_VM_VENDOR + " " + JAVA_VM_NAME + " " + JAVA_VERSION;
  private static final String OS_NAME = System.getProperty("os.name");
  private static String HOSTNAME;
  private static final Logger log = LoggerFactory.getLogger(EventEnrichment.class);

  public static void enrich(Attack010 attack, DataBundle appSecCtx) {
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
    if (request.getScheme() == null) {
      request.setScheme("http"); // XXX: hardcoded now
    }
    if (request.getMethod() == null) {
      request.setMethod("GET"); // XXX: hardcoded now
    }
    if (request.getUrl() == null) {
      request.setUrl("http://example.com/"); // XXX: hardcoded now
    }
    if (request.getHost() == null) {
      request.setHost("example.com"); // XXX: hardcoded now
    }
    if (request.getPort() == null) {
      request.setPort(80); // XXX: hardcoded now
    }
    if (request.getPath() == null) {
      String s = appSecCtx.get(KnownAddresses.REQUEST_URI_RAW);
      if (s == null) {
        log.warn("Request path not available");
        s = "/UNKNOWN"; // XXX
      }
      if (s.contains("?")) {
        s = s.substring(0, s.indexOf("?"));
      }
      request.setPath(s);
    }
    if (request.getRemoteIp() == null) {
      request.setRemoteIp("255.255.255.255"); // XXX: hardcoded now
    }
    if (request.getRemotePort() == null) {
      request.setRemotePort(65535); // XXX: hardcoded now
    }
    if (request.getHeaders() == null) {
      CaseInsensitiveMap<List<String>> headers = appSecCtx.get(KnownAddresses.HEADERS_NO_COOKIES);
      if (headers != null) {
        request.setHeaders(new HttpHeaders(headers));
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
}
