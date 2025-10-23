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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: different methods to be called by different parts perhaps splitting it would make sense
// or at least create separate interfaces
@SuppressFBWarnings("AT_STALE_THREAD_WRITE_OF_PRIMITIVE")
public class AppSecRequestContext implements DataBundle, Closeable {
  private static final Logger log = LoggerFactory.getLogger(AppSecRequestContext.class);

  public static final int DEFAULT_EXTENDED_DATA_COLLECTION_MAX_HEADERS = 50;

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

  // headers related with authorization
  public static final Set<String> AUTHORIZATION_HEADERS =
      new TreeSet<>(
          Arrays.asList(
              "authorization",
              "proxy-authorization",
              "www-authenticate",
              "proxy-authenticate",
              "authentication-info",
              "proxy-authentication-info",
              "cookie",
              "set-cookie"));

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

  private boolean extendedDataCollection = false;
  private int extendedDataCollectionMaxHeaders = DEFAULT_EXTENDED_DATA_COLLECTION_MAX_HEADERS;

  private volatile StoredBodySupplier storedRequestBodySupplier;
  private String dbType;

  private int responseStatus;

  private boolean reqDataPublished;
  private boolean rawReqBodyPublished;
  private boolean convertedReqBodyPublished;
  private boolean responseBodyPublished;
  private boolean respDataPublished;
  private boolean pathParamsPublished;
  private volatile Map<String, Object> derivatives;

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
  private volatile boolean processedResponseBodySizeExceeded;
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

  private final AtomicInteger httpClientRequestCount = new AtomicInteger(0);
  private final Set<Long> sampledHttpClientRequests = new HashSet<>();

  private static final AtomicIntegerFieldUpdater<AppSecRequestContext> WAF_TIMEOUTS_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(AppSecRequestContext.class, "wafTimeouts");
  private static final AtomicIntegerFieldUpdater<AppSecRequestContext> RASP_TIMEOUTS_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(AppSecRequestContext.class, "raspTimeouts");
  private boolean manuallyKept = false;

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

  public boolean sampleHttpClientRequest(final long id) {
    httpClientRequestCount.incrementAndGet();
    synchronized (sampledHttpClientRequests) {
      if (sampledHttpClientRequests.size()
          < Config.get().getApiSecurityMaxDownstreamRequestBodyAnalysis()) {
        sampledHttpClientRequests.add(id);
        return true;
      }
    }
    return false;
  }

  public boolean isHttpClientRequestSampled(final long id) {
    return sampledHttpClientRequests.contains(id);
  }

  public int getHttpClientRequestCount() {
    return httpClientRequestCount.get();
  }

  public int getWafTimeouts() {
    return wafTimeouts;
  }

  public int getRaspTimeouts() {
    return raspTimeouts;
  }

  public boolean isExtendedDataCollection() {
    return extendedDataCollection;
  }

  public void setExtendedDataCollection(boolean extendedDataCollection) {
    this.extendedDataCollection = extendedDataCollection;
  }

  public int getExtendedDataCollectionMaxHeaders() {
    return extendedDataCollectionMaxHeaders;
  }

  public void setExtendedDataCollectionMaxHeaders(int extendedDataCollectionMaxHeaders) {
    this.extendedDataCollectionMaxHeaders = extendedDataCollectionMaxHeaders;
  }

  public WafContext getOrCreateWafContext(
      WafHandle wafHandle, boolean createMetrics, boolean isRasp) {
    if (createMetrics) {
      if (wafMetrics == null) {
        this.wafMetrics = new WafMetrics();
      }
      if (isRasp && raspMetrics == null) {
        this.raspMetrics = new WafMetrics();
      }
    }

    WafContext curWafContext;
    synchronized (this) {
      curWafContext = this.wafContext;
      if (curWafContext != null) {
        return curWafContext;
      }
      curWafContext = new WafContext(wafHandle);
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

  public boolean isResponseBodyPublished() {
    return responseBodyPublished;
  }

  public void setResponseBodyPublished(final boolean responseBodyPublished) {
    this.responseBodyPublished = responseBodyPublished;
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

  /**
   * Attempts to parse a string value as a number. Returns the parsed number if successful, null
   * otherwise. Tries to parse as integer first, then as double if it contains a decimal point.
   */
  private static Number convertToNumericAttribute(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      // Check if it contains a decimal point to determine if it's a double
      if (value.contains(".")) {
        return Double.parseDouble(value);
      } else {
        // Try to parse as integer first
        return Long.parseLong(value);
      }
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public void reportDerivatives(Map<String, Object> data) {
    log.debug("Reporting derivatives: {}", data);
    if (data == null || data.isEmpty()) return;

    // Store raw derivatives
    if (derivatives == null) {
      derivatives = new HashMap<>();
    }

    // Process each attribute according to the specification
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      String attributeKey = entry.getKey();
      Object attributeConfig = entry.getValue();

      if (attributeConfig instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) attributeConfig;

        // Check if it's a literal value schema
        if (config.containsKey("value")) {
          Object literalValue = config.get("value");
          if (literalValue != null) {
            // Preserve the original type - don't convert to string
            derivatives.put(attributeKey, literalValue);
            log.debug(
                "Added literal attribute: {} = {} (type: {})",
                attributeKey,
                literalValue,
                literalValue.getClass().getSimpleName());
          }
        }
        // Check if it's a request data schema
        else if (config.containsKey("address")) {
          String address = (String) config.get("address");
          @SuppressWarnings("unchecked")
          List<String> keyPath = (List<String>) config.get("key_path");
          @SuppressWarnings("unchecked")
          List<String> transformers = (List<String>) config.get("transformers");

          Object extractedValue = extractValueFromRequestData(address, keyPath, transformers);
          if (extractedValue != null) {
            // For extracted values, convert to string as they come from request data
            derivatives.put(attributeKey, extractedValue.toString());
            log.debug("Added extracted attribute: {} = {}", attributeKey, extractedValue);
          }
        }
      } else {
        // Handle plain string/numeric values
        derivatives.put(attributeKey, attributeConfig);
        log.debug("Added direct attribute: {} = {}", attributeKey, attributeConfig);
      }
    }
  }

  /**
   * Extracts a value from request data based on address, key path, and transformers.
   *
   * @param address The address to extract from (e.g., "server.request.headers")
   * @param keyPath Optional key path to navigate the data structure
   * @param transformers Optional list of transformers to apply
   * @return The extracted value, or null if not found
   */
  private Object extractValueFromRequestData(
      String address, List<String> keyPath, List<String> transformers) {
    // Get the data from the address
    Object data = getDataForAddress(address);
    if (data == null) {
      log.debug("No data found for address: {}", address);
      return null;
    }

    // Navigate through the key path
    Object currentValue = data;
    if (keyPath != null && !keyPath.isEmpty()) {
      currentValue = navigateKeyPath(currentValue, keyPath);
      if (currentValue == null) {
        log.debug("Could not navigate key path {} for address {}", keyPath, address);
        return null;
      }
    }

    // Apply transformers if specified
    if (transformers != null && !transformers.isEmpty()) {
      currentValue = applyTransformers(currentValue, transformers);
    }

    return currentValue;
  }

  /** Gets data for a specific address from the request context. */
  private Object getDataForAddress(String address) {
    // Map common addresses to our data structures
    switch (address) {
      case "server.request.headers":
        return requestHeaders;
      case "server.response.headers":
        return responseHeaders;
      case "server.request.cookies":
        return collectedCookies;
      case "server.request.uri.raw":
        return savedRawURI;
      case "server.request.method":
        return method;
      case "server.request.scheme":
        return scheme;
      case "server.request.route":
        return route;
      case "server.response.status":
        return responseStatus;
      case "server.request.body":
        return getStoredRequestBody();
      case "usr.id":
        return userId;
      case "usr.login":
        return userLogin;
      case "usr.session_id":
        return sessionId;
      default:
        log.debug("Unknown address: {}", address);
        return null;
    }
  }

  /** Navigates through a data structure using a key path. */
  private Object navigateKeyPath(Object data, List<String> keyPath) {
    Object current = data;

    for (String key : keyPath) {
      if (current instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) current;
        current = map.get(key);
      } else if (current instanceof List) {
        try {
          int index = Integer.parseInt(key);
          @SuppressWarnings("unchecked")
          List<Object> list = (List<Object>) current;
          if (index >= 0 && index < list.size()) {
            current = list.get(index);
          } else {
            return null;
          }
        } catch (NumberFormatException e) {
          log.debug("Invalid list index: {}", key);
          return null;
        }
      } else {
        log.debug("Cannot navigate key {} in data type: {}", key, current.getClass());
        return null;
      }

      if (current == null) {
        return null;
      }
    }

    return current;
  }

  /** Applies transformers to a value. */
  private Object applyTransformers(Object value, List<String> transformers) {
    Object current = value;

    for (String transformer : transformers) {
      switch (transformer) {
        case "lowercase":
          if (current instanceof String) {
            current = ((String) current).toLowerCase(Locale.ROOT);
          }
          break;
        case "uppercase":
          if (current instanceof String) {
            current = ((String) current).toUpperCase(Locale.ROOT);
          }
          break;
        case "trim":
          if (current instanceof String) {
            current = ((String) current).trim();
          }
          break;
        case "length":
          if (current instanceof String) {
            current = ((String) current).length();
          } else if (current instanceof Collection) {
            current = ((Collection<?>) current).size();
          } else if (current instanceof Map) {
            current = ((Map<?, ?>) current).size();
          }
          break;
        default:
          log.debug("Unknown transformer: {}", transformer);
          break;
      }
    }

    return current;
  }

  public boolean commitDerivatives(TraceSegment traceSegment) {
    log.debug("Committing derivatives: {} for {}", derivatives, traceSegment);
    if (traceSegment == null) {
      return false;
    }

    // Process and commit derivatives directly
    if (derivatives != null && !derivatives.isEmpty()) {
      for (Map.Entry<String, Object> entry : derivatives.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();

        // Handle different value types
        if (value instanceof Number) {
          traceSegment.setTagTop(key, (Number) value);
        } else if (value instanceof String) {
          // Try to parse as numeric, otherwise use as string
          Number parsedNumber = convertToNumericAttribute((String) value);
          if (parsedNumber != null) {
            traceSegment.setTagTop(key, parsedNumber);
          } else {
            traceSegment.setTagTop(key, value);
          }
        } else if (value instanceof Boolean) {
          traceSegment.setTagTop(key, value);
        } else {
          // Convert other types to string
          traceSegment.setTagTop(key, value.toString());
        }
      }
    }

    // Clear all attribute maps
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

  public boolean isProcessedResponseBodySizeExceeded() {
    return processedResponseBodySizeExceeded;
  }

  public void setProcessedResponseBodySizeExceeded(boolean processedResponseBodySizeExceeded) {
    this.processedResponseBodySizeExceeded = processedResponseBodySizeExceeded;
  }

  public boolean isRaspMatched() {
    return raspMatched;
  }

  public void setRaspMatched(boolean raspMatched) {
    this.raspMatched = raspMatched;
  }

  public boolean isManuallyKept() {
    return manuallyKept;
  }

  public void setManuallyKept(boolean manuallyKept) {
    this.manuallyKept = manuallyKept;
  }
}
