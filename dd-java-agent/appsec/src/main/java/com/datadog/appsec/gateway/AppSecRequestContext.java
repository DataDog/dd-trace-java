package com.datadog.appsec.gateway;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.util.Collections.emptySet;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.util.StandardizedLogging;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.datadog.ddwaf.WafMetrics;
import datadog.trace.api.Config;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.util.stacktrace.StackTraceEvent;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: different methods to be called by different parts perhaps splitting it would make sense
// or at least create separate interfaces
public class AppSecRequestContext implements DataBundle, Closeable {
  private static final Logger log = LoggerFactory.getLogger(AppSecRequestContext.class);

  // Values MUST be lowercase! Lookup with Ignore Case
  // was removed due performance reason
  // request headers that will always be set when appsec is enabled
  public static final Set<String> DEFAULT_REQUEST_HEADERS_ALLOW_LIST =
      new TreeSet<>(
          Arrays.asList(
              "content-type",
              "user-agent",
              "accept",
              "x-amzn-trace-id",
              "cloudfront-viewer-ja3-fingerprint",
              "cf-ray",
              "x-cloud-trace-context",
              "x-appgw-trace-id",
              "x-sigsci-requestid",
              "x-sigsci-tags",
              "akamai-user-risk"));

  // request headers when there are security events
  public static final Set<String> REQUEST_HEADERS_ALLOW_LIST =
      new TreeSet<>(
          Arrays.asList(
              "x-forwarded-for",
              "x-real-ip",
              "true-client-ip",
              "x-client-ip",
              "x-forwarded",
              "forwarded-for",
              "x-cluster-client-ip",
              "fastly-client-ip",
              "cf-connecting-ip",
              "cf-connecting-ipv6",
              "forwarded",
              "via",
              "content-length",
              "content-encoding",
              "content-language",
              "host",
              "accept-encoding",
              "accept-language"));

  // response headers when there are security events
  public static final Set<String> RESPONSE_HEADERS_ALLOW_LIST =
      new TreeSet<>(
          Arrays.asList("content-length", "content-type", "content-encoding", "content-language"));

  static {
    REQUEST_HEADERS_ALLOW_LIST.addAll(DEFAULT_REQUEST_HEADERS_ALLOW_LIST);
  }

  private final ConcurrentHashMap<Address<?>, Object> persistentData = new ConcurrentHashMap<>();
  private volatile Queue<AppSecEvent> appSecEvents;
  private volatile Queue<StackTraceEvent> stackTraceEvents;

  // assume these will always be written and read by the same thread
  private String scheme;
  private String method;
  private String savedRawURI;
  private String route;
  private final Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
  private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
  private volatile Map<String, List<String>> collectedCookies;
  private boolean finishedRequestHeaders;
  private boolean finishedResponseHeaders;
  private String peerAddress;
  private int peerPort;
  private String inferredClientIp;

  private volatile StoredBodySupplier storedRequestBodySupplier;
  private String dbType;

  private int responseStatus;

  private boolean reqDataPublished;
  private boolean rawReqBodyPublished;
  private boolean convertedReqBodyPublished;
  private boolean respDataPublished;
  private boolean pathParamsPublished;
  private volatile Map<String, String> derivatives;

  private final AtomicBoolean rateLimited = new AtomicBoolean(false);
  private volatile boolean throttled;

  // should be guarded by this
  private volatile WafContext wafContext;
  private volatile boolean wafContextClosed;
  // set after wafContext is set
  private volatile WafMetrics wafMetrics;
  private volatile WafMetrics raspMetrics;
  private final AtomicInteger raspMetricsCounter = new AtomicInteger(0);

  private volatile boolean wafBlocked;
  private volatile boolean wafErrors;
  private volatile boolean wafTruncated;
  private volatile boolean wafRequestBlockFailure;
  private volatile boolean wafRateLimited;

  private volatile int wafTimeouts;
  private volatile int raspTimeouts;

  private volatile Object processedRequestBody;
  private volatile boolean raspMatched;

  // keep a reference to the last published usr.id
  private volatile String userId;
  // keep a reference to the last published usr.login
  private volatile String userLogin;
  // keep a reference to the last published usr.session_id
  private volatile String sessionId;

  // Used to detect missing request-end event at close.
  private volatile boolean requestEndCalled;

  private volatile boolean keepOpenForApiSecurityPostProcessing;
  private volatile Long apiSecurityEndpointHash;

  private static final AtomicIntegerFieldUpdater<AppSecRequestContext> WAF_TIMEOUTS_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(AppSecRequestContext.class, "wafTimeouts");
  private static final AtomicIntegerFieldUpdater<AppSecRequestContext> RASP_TIMEOUTS_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(AppSecRequestContext.class, "raspTimeouts");

  // to be called by the Event Dispatcher
  public void addAll(DataBundle newData) {
    for (Map.Entry<Address<?>, Object> entry : newData) {
      Address<?> address = entry.getKey();
      Object value = entry.getValue();
      if (value == null) {
        log.debug(SEND_TELEMETRY, "Address {} ignored, because contains null value.", address);
        continue;
      }
      Object prev = persistentData.putIfAbsent(address, value);
      if (prev == value || value.equals(prev)) {
        continue;
      } else if (prev != null) {
        log.debug(SEND_TELEMETRY, "Attempt to replace context value for {}", address);
      }
      if (log.isDebugEnabled()) {
        StandardizedLogging.addressPushed(log, address);
      }
    }
  }

  public WafMetrics getWafMetrics() {
    return wafMetrics;
  }

  public WafMetrics getRaspMetrics() {
    return raspMetrics;
  }

  public AtomicInteger getRaspMetricsCounter() {
    return raspMetricsCounter;
  }

  public void setWafBlocked() {
    this.wafBlocked = true;
  }

  public boolean isWafBlocked() {
    return wafBlocked;
  }

  public void setWafErrors() {
    this.wafErrors = true;
  }

  public boolean hasWafErrors() {
    return wafErrors;
  }

  public void setWafTruncated() {
    this.wafTruncated = true;
  }

  public boolean isWafTruncated() {
    return wafTruncated;
  }

  public void setWafRequestBlockFailure() {
    this.wafRequestBlockFailure = true;
  }

  public boolean isWafRequestBlockFailure() {
    return wafRequestBlockFailure;
  }

  public void setWafRateLimited() {
    this.wafRateLimited = true;
  }

  public boolean isWafRateLimited() {
    return wafRateLimited;
  }

  public void increaseWafTimeouts() {
    WAF_TIMEOUTS_UPDATER.incrementAndGet(this);
  }

  public void increaseRaspTimeouts() {
    RASP_TIMEOUTS_UPDATER.incrementAndGet(this);
  }

  public int getWafTimeouts() {
    return wafTimeouts;
  }

  public int getRaspTimeouts() {
    return raspTimeouts;
  }

  public WafContext getOrCreateWafContext(WafHandle ctx, boolean createMetrics, boolean isRasp) {

    if (createMetrics) {
      if (wafMetrics == null) {
        this.wafMetrics = ctx.createMetrics();
      }
      if (isRasp && raspMetrics == null) {
        this.raspMetrics = ctx.createMetrics();
      }
    }

    WafContext curWafContext;
    synchronized (this) {
      curWafContext = this.wafContext;
      if (curWafContext != null) {
        return curWafContext;
      }
      curWafContext = ctx.openContext();
      this.wafContext = curWafContext;
    }
    return curWafContext;
  }

  public void closeWafContext() {
    if (wafContext != null) {
      synchronized (this) {
        if (wafContext != null) {
          try {
            wafContextClosed = true;
            wafContext.close();
          } finally {
            wafContext = null;
          }
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

  public String getMethod() {
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

  public String getRoute() {
    return route;
  }

  public void setRoute(String route) {
    this.route = route;
  }

  public void setKeepOpenForApiSecurityPostProcessing(final boolean flag) {
    this.keepOpenForApiSecurityPostProcessing = flag;
  }

  public boolean isKeepOpenForApiSecurityPostProcessing() {
    return this.keepOpenForApiSecurityPostProcessing;
  }

  public void setApiSecurityEndpointHash(long hash) {
    this.apiSecurityEndpointHash = hash;
  }

  public Long getApiSecurityEndpointHash() {
    return this.apiSecurityEndpointHash;
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

  public String getDbType() {
    return dbType;
  }

  public void setDbType(String dbType) {
    this.dbType = dbType;
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
    return pathParamsPublished;
  }

  public void setPathParamsPublished(boolean pathParamsPublished) {
    this.pathParamsPublished = pathParamsPublished;
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

  /**
   * Updates the current used usr.id
   *
   * @return {@code false} if the user id has not been updated
   */
  public boolean updateUserId(String userId) {
    if (Objects.equals(this.userId, userId)) {
      return false;
    }
    this.userId = userId;
    return true;
  }

  /**
   * Updates current used usr.login
   *
   * @return {@code false} if the user login has not been updated
   */
  public boolean updateUserLogin(String userLogin) {
    if (Objects.equals(this.userLogin, userLogin)) {
      return false;
    }
    this.userLogin = userLogin;
    return true;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getSessionId() {
    return sessionId;
  }

  /**
   * Close the context and release all resources. This method is idempotent and can be called
   * multiple times. For each root span, this method is always called from
   * CoreTracer#onRootSpanPublished.
   */
  @Override
  public void close() {
    if (!requestEndCalled) {
      log.debug(SEND_TELEMETRY, "Request end event was not called before close");
    }
    // For API Security, we sometimes keep contexts open for late processing. In that case, this
    // flag needs to be
    // later reset by the API Security post-processor and close must be called again.
    if (!keepOpenForApiSecurityPostProcessing) {
      if (wafContext != null) {
        log.debug(
            SEND_TELEMETRY, "WAF object had not been closed (probably missed request-end event)");
        closeWafContext();
      }
      collectedCookies = null;
      requestHeaders.clear();
      responseHeaders.clear();
      persistentData.clear();
      if (derivatives != null) {
        derivatives.clear();
        derivatives = null;
      }
    }
  }

  /** @return the portion of the body read so far, if any */
  public CharSequence getStoredRequestBody() {
    StoredBodySupplier storedRequestBodySupplier = this.storedRequestBodySupplier;
    if (storedRequestBodySupplier == null) {
      return null;
    }
    return storedRequestBodySupplier.get();
  }

  public void reportEvents(Collection<AppSecEvent> appSecEvents) {
    for (AppSecEvent event : appSecEvents) {
      StandardizedLogging.attackDetected(log, event);
    }
    if (this.appSecEvents == null) {
      synchronized (this) {
        if (this.appSecEvents == null) {
          this.appSecEvents = new ConcurrentLinkedQueue<>();
        }
      }
    }
    this.appSecEvents.addAll(appSecEvents);
  }

  public void reportStackTrace(StackTraceEvent stackTraceEvent) {
    if (this.stackTraceEvents == null) {
      synchronized (this) {
        if (this.stackTraceEvents == null) {
          this.stackTraceEvents = new ConcurrentLinkedQueue<>();
        }
      }
    }
    if (stackTraceEvents.size() <= Config.get().getAppSecMaxStackTraces()) {
      this.stackTraceEvents.add(stackTraceEvent);
    }
  }

  Collection<AppSecEvent> transferCollectedEvents() {
    if (this.appSecEvents == null) {
      return Collections.emptyList();
    }

    Collection<AppSecEvent> events = new ArrayList<>();
    AppSecEvent item;
    while ((item = this.appSecEvents.poll()) != null) {
      events.add(item);
    }

    return events;
  }

  List<StackTraceEvent> getStackTraces() {
    if (this.stackTraceEvents == null) {
      return null;
    }
    List<StackTraceEvent> stackTraces = new ArrayList<>();
    StackTraceEvent item;
    while ((item = this.stackTraceEvents.poll()) != null) {
      stackTraces.add(item);
    }
    return stackTraces;
  }

  public void reportDerivatives(Map<String, String> data) {
    log.debug("Reporting derivatives: {}", data);
    if (data == null || data.isEmpty()) return;

    if (derivatives == null) {
      derivatives = data;
    } else {
      derivatives.putAll(data);
    }
  }

  public boolean commitDerivatives(TraceSegment traceSegment) {
    log.debug("Committing derivatives: {} for {}", derivatives, traceSegment);
    if (traceSegment == null || derivatives == null) {
      return false;
    }
    derivatives.forEach(traceSegment::setTagTop);
    log.debug("Committed derivatives: {} for {}", derivatives, traceSegment);
    derivatives = null;
    return true;
  }

  // Mainly used for testing and logging
  Set<String> getDerivativeKeys() {
    return derivatives == null ? emptySet() : new HashSet<>(derivatives.keySet());
  }

  public boolean isThrottled(RateLimiter rateLimiter) {
    if (rateLimiter != null && rateLimited.compareAndSet(false, true)) {
      throttled = rateLimiter.isThrottled();
    }
    return throttled;
  }

  public boolean isWafContextClosed() {
    return wafContextClosed;
  }

  /** Must be called during request end event processing. */
  void setRequestEndCalled() {
    requestEndCalled = true;
  }

  public void setProcessedRequestBody(Object processedRequestBody) {
    this.processedRequestBody = processedRequestBody;
  }

  public Object getProcessedRequestBody() {
    return processedRequestBody;
  }

  public boolean isRaspMatched() {
    return raspMatched;
  }

  public void setRaspMatched(boolean raspMatched) {
    this.raspMatched = raspMatched;
  }
}
