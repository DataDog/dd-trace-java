package com.datadog.appsec.gateway;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.trace.api.http.StoredBodySupplier;
import io.sqreen.powerwaf.Additive;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.PowerwafMetrics;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: different methods to be called by different parts perhaps splitting it would make sense
// or at least create separate interfaces
public class AppSecRequestContext implements DataBundle, Closeable {
  private static final Logger log = LoggerFactory.getLogger(AppSecRequestContext.class);

  // Values MUST be lowercase! Lookup with Ignore Case
  // was removed due performance reason
  public static final Set<String> HEADERS_ALLOW_LIST =
      new TreeSet<>(
          Arrays.asList(
              "x-forwarded-for",
              "x-client-ip",
              "x-real-ip",
              "x-forwarded",
              "x-cluster-client-ip",
              "forwarded-for",
              "forwarded",
              "via",
              "client-ip",
              "true-client-ip",
              "fastly-client-ip",
              "cf-connecting-ip",
              "cf-connecting-ipv6",
              "content-length",
              "content-type",
              "content-encoding",
              "content-language",
              "host",
              "user-agent",
              "accept",
              "accept-encoding",
              "accept-language"));

  private final ConcurrentHashMap<Address<?>, Object> persistentData = new ConcurrentHashMap<>();
  private Collection<AppSecEvent100> collectedEvents; // guarded by this

  // assume these will always be written and read by the same thread
  private String scheme;
  private String method;
  private String savedRawURI;
  private final Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
  private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
  private Map<String, List<String>> collectedCookies;
  private boolean finishedRequestHeaders;
  private boolean finishedResponseHeaders;
  private String peerAddress;
  private int peerPort;
  private String inferredClientIp;

  private volatile StoredBodySupplier storedRequestBodySupplier;

  private int responseStatus;

  private boolean reqDataPublished;
  private boolean rawReqBodyPublished;
  private boolean convertedReqBodyPublished;
  private boolean respDataPublished;

  // should be guarded by this
  private Additive additive;
  // set after additive is set
  private volatile PowerwafMetrics wafMetrics;

  // to be called by the Event Dispatcher
  public void addAll(DataBundle newData) {
    for (Map.Entry<Address<?>, Object> entry : newData) {
      Address<?> address = entry.getKey();
      Object value = entry.getValue();
      if (value == null) {
        log.warn("Address {} ignored, because contains null value.", address);
        continue;
      }
      Object prev = persistentData.putIfAbsent(address, value);
      if (prev == value) {
        continue;
      } else if (prev != null) {
        log.warn("Illegal attempt to replace context value for {}", address);
      }
      if (log.isDebugEnabled()) {
        StandardizedLogging.addressPushed(log, address);
      }
    }
  }

  public PowerwafMetrics getWafMetrics() {
    return wafMetrics;
  }

  public Additive getOrCreateAdditive(PowerwafContext ctx, boolean createMetrics) {
    Additive curAdditive;
    synchronized (this) {
      curAdditive = this.additive;
      if (curAdditive != null) {
        return curAdditive;
      }
      curAdditive = ctx.openAdditive();
      this.additive = curAdditive;
    }

    // new additive was created
    if (createMetrics) {
      this.wafMetrics = ctx.createMetrics();
    }
    return curAdditive;
  }

  public void closeAdditive() {
    synchronized (this) {
      if (additive != null) {
        try {
          additive.close();
        } finally {
          additive = null;
        }
      }
    }
  }

  /* Implementation of DataBundle */

  @Override
  public boolean hasAddress(Address<?> addr) {
    return persistentData.containsKey(addr);
  }

  @Override
  public Collection<Address<?>> getAllAddresses() {
    return persistentData.keySet();
  }

  @Override
  public int size() {
    return persistentData.size();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(Address<T> addr) {
    return (T) persistentData.get(addr);
  }

  @Override
  public Iterator<Map.Entry<Address<?>, Object>> iterator() {
    return persistentData.entrySet().iterator();
  }

  /* Interface for use of GatewayBridge */

  String getScheme() {
    return scheme;
  }

  void setScheme(String scheme) {
    this.scheme = scheme;
  }

  String getMethod() {
    return method;
  }

  void setMethod(String method) {
    this.method = method;
  }

  String getSavedRawURI() {
    return savedRawURI;
  }

  void setRawURI(String savedRawURI) {
    if (this.savedRawURI != null && this.savedRawURI.compareToIgnoreCase(savedRawURI) != 0) {
      throw new IllegalStateException(
          "Forbidden attempt to set different raw URI for given request context");
    }
    this.savedRawURI = savedRawURI;
  }

  void addRequestHeader(String name, String value) {
    if (finishedRequestHeaders) {
      throw new IllegalStateException("Request headers were said to be finished before");
    }

    if (name == null || value == null) {
      return;
    }

    List<String> strings =
        requestHeaders.computeIfAbsent(name.toLowerCase(Locale.ROOT), h -> new ArrayList<>(1));
    strings.add(value);
  }

  void finishRequestHeaders() {
    this.finishedRequestHeaders = true;
  }

  boolean isFinishedRequestHeaders() {
    return finishedRequestHeaders;
  }

  Map<String, List<String>> getRequestHeaders() {
    return requestHeaders;
  }

  void addResponseHeader(String name, String value) {
    if (finishedResponseHeaders) {
      throw new IllegalStateException("Response headers were said to be finished before");
    }

    if (name == null || value == null) {
      return;
    }

    List<String> strings =
        responseHeaders.computeIfAbsent(name.toLowerCase(Locale.ROOT), h -> new ArrayList<>(1));
    strings.add(value);
  }

  public void finishResponseHeaders() {
    this.finishedResponseHeaders = true;
  }

  public boolean isFinishedResponseHeaders() {
    return finishedResponseHeaders;
  }

  Map<String, List<String>> getResponseHeaders() {
    return responseHeaders;
  }

  void addCookies(Map<String, List<String>> cookies) {
    if (finishedRequestHeaders) {
      throw new IllegalStateException("Request headers were said to be finished before");
    }
    if (collectedCookies == null) {
      collectedCookies = cookies;
    } else {
      collectedCookies.putAll(cookies);
    }
  }

  Map<String, ? extends Collection<String>> getCookies() {
    return collectedCookies != null ? collectedCookies : Collections.emptyMap();
  }

  String getPeerAddress() {
    return peerAddress;
  }

  void setPeerAddress(String peerAddress) {
    this.peerAddress = peerAddress;
  }

  public int getPeerPort() {
    return peerPort;
  }

  public void setPeerPort(int peerPort) {
    this.peerPort = peerPort;
  }

  void setInferredClientIp(String ipAddress) {
    this.inferredClientIp = ipAddress;
  }

  String getInferredClientIp() {
    return inferredClientIp;
  }

  void setStoredRequestBodySupplier(StoredBodySupplier storedRequestBodySupplier) {
    this.storedRequestBodySupplier = storedRequestBodySupplier;
  }

  public int getResponseStatus() {
    return responseStatus;
  }

  public void setResponseStatus(int responseStatus) {
    this.responseStatus = responseStatus;
  }

  public boolean isReqDataPublished() {
    return reqDataPublished;
  }

  public void setReqDataPublished(boolean reqDataPublished) {
    this.reqDataPublished = reqDataPublished;
  }

  public boolean isPathParamsPublished() {
    return persistentData.containsKey(KnownAddresses.REQUEST_PATH_PARAMS);
  }

  public boolean isRawReqBodyPublished() {
    return rawReqBodyPublished;
  }

  public void setRawReqBodyPublished(boolean rawReqBodyPublished) {
    this.rawReqBodyPublished = rawReqBodyPublished;
  }

  public boolean isConvertedReqBodyPublished() {
    return convertedReqBodyPublished;
  }

  public void setConvertedReqBodyPublished(boolean convertedReqBodyPublished) {
    this.convertedReqBodyPublished = convertedReqBodyPublished;
  }

  public boolean isRespDataPublished() {
    return respDataPublished;
  }

  public void setRespDataPublished(boolean respDataPublished) {
    this.respDataPublished = respDataPublished;
  }

  @Override
  public void close() {
    synchronized (this) {
      if (additive == null) {
        return;
      }
    }

    log.warn("WAF object had not been closed (probably missed request-end event)");
    closeAdditive();
  }

  /* end interface for GatewayBridge */

  /* Should be accessible from the modules */

  /** @return the portion of the body read so far, if any */
  public CharSequence getStoredRequestBody() {
    StoredBodySupplier storedRequestBodySupplier = this.storedRequestBodySupplier;
    if (storedRequestBodySupplier == null) {
      return null;
    }
    return storedRequestBodySupplier.get();
  }

  public void reportEvents(Collection<AppSecEvent100> events) {
    for (AppSecEvent100 event : events) {
      StandardizedLogging.attackDetected(log, event);
    }
    synchronized (this) {
      if (this.collectedEvents == null) {
        this.collectedEvents = new ArrayList<>();
      }
      try {
        this.collectedEvents.addAll(events);
      } catch (UnsupportedOperationException e) {
        throw new IllegalStateException("Events cannot be added anymore");
      }
    }
  }

  Collection<AppSecEvent100> transferCollectedEvents() {
    Collection<AppSecEvent100> events;
    synchronized (this) {
      events = this.collectedEvents;
      this.collectedEvents = Collections.emptyList();
    }
    if (events != null) {
      return events;
    } else {
      return Collections.emptyList();
    }
  }
}
