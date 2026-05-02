package com.datadog.appsec.ddwaf;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.stacktrace.StackTraceEvent.DEFAULT_LANGUAGE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.RateLimiter;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.util.StandardizedLogging;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.datadog.ddwaf.WafMetrics;
import com.datadog.ddwaf.exception.AbstractWafException;
import com.datadog.ddwaf.exception.TimeoutWafException;
import com.datadog.ddwaf.exception.UnclassifiedWafException;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.metrics.api.Counter;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import datadog.trace.util.stacktrace.StackUtils;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WAFModule implements AppSecModule {
  private static final Logger log = LoggerFactory.getLogger(WAFModule.class);

  public static final int MAX_DEPTH = 20;
  public static final int MAX_ELEMENTS = 256;
  public static final int MAX_STRING_SIZE = 4096;
  private static volatile Waf.Limits LIMITS;
  private static final Class<?> PROXY_CLASS =
      Proxy.getProxyClass(WAFModule.class.getClassLoader(), Set.class);
  private static final Constructor<?> PROXY_CLASS_CONSTRUCTOR;

  private static final JsonAdapter<List<WAFResultData>> RES_JSON_ADAPTER;

  private static final String EXPLOIT_DETECTED_MSG = "Exploit detected";
  private boolean init = true;
  private String rulesetVersion;
  private WafBuilder wafBuilder;

  private static class ActionInfo {
    final String type;
    final Map<String, Object> parameters;

    private ActionInfo(String type, Map<String, Object> parameters) {
      this.type = type;
      this.parameters = parameters;
    }
  }

  private static class CtxAndAddresses {
    final Collection<Address<?>> addressesOfInterest;
    final WafHandle ctx;

    private CtxAndAddresses(Collection<Address<?>> addressesOfInterest, WafHandle ctx) {
      this.addressesOfInterest = addressesOfInterest;
      this.ctx = ctx;
    }
  }

  static {
    try {
      PROXY_CLASS_CONSTRUCTOR = PROXY_CLASS.getConstructor(InvocationHandler.class);
    } catch (NoSuchMethodException e) {
      throw new UndeclaredThrowableException(e);
    }

    Moshi moshi = new Moshi.Builder().build();
    RES_JSON_ADAPTER = moshi.adapter(Types.newParameterizedType(List.class, WAFResultData.class));

    createLimitsObject();
  }

  // used in testing
  static void createLimitsObject() {
    LIMITS =
        new Waf.Limits(
            MAX_DEPTH,
            MAX_ELEMENTS,
            MAX_STRING_SIZE,
            /* set effectively infinite budgets. Don't use Long.MAX_VALUE, because
             * traditionally ddwaf has had problems with too large budgets */
            ((long) Integer.MAX_VALUE) * 1000,
            Config.get().getAppSecWafTimeout());
  }

  private final boolean wafMetricsEnabled =
      Config.get().isAppSecWafMetrics(); // could be static if not for tests
  private final AtomicReference<CtxAndAddresses> ctxAndAddresses = new AtomicReference<>();
  private final RateLimiter rateLimiter;

  public WAFModule() {
    this(null);
  }

  public WAFModule(Monitoring monitoring) {
    this.rateLimiter = getRateLimiter(monitoring);
  }

  @Override
  public void config(AppSecModuleConfigurer appSecConfigService)
      throws AppSecModuleActivationException {
    appSecConfigService.addSubConfigListener("waf", (key, reconf) -> applyConfig(reconf));

    ProductActivation appSecEnabledConfig = Config.get().getAppSecActivation();
    if (appSecEnabledConfig == ProductActivation.FULLY_ENABLED) {
      try {
        applyConfig(AppSecModuleConfigurer.Reconfiguration.NOOP);
      } catch (ClassCastException e) {
        throw new AppSecModuleActivationException("Config expected to be a map", e);
      } catch (AbstractWafException e) {
        throw new AppSecModuleActivationException("Could not apply config", e);
      }
    }
  }

  @Override
  public void setWafBuilder(WafBuilder wafBuilder) {
    this.wafBuilder = wafBuilder;
  }

  // this function is called from one thread in the beginning that's different
  // from the RC thread that calls it later on
  private void applyConfig(AppSecModuleConfigurer.Reconfiguration reconf)
      throws AppSecModuleActivationException, AbstractWafException {
    boolean success = false;
    if (init) {
      log.debug("Initializing WAF");
    } else {
      log.debug("Updating WAF");
    }
    try {
      initOrUpdateWafHandle(reconf);
      success = true;
    } catch (Exception e) {
      throw new AppSecModuleActivationException("Could not initialize waf handle", e);
    } finally {
      if (init) {
        WafMetricCollector.get().wafInit(Waf.LIB_VERSION, rulesetVersion, success);
        init = false;
      } else {
        WafMetricCollector.get().wafUpdates(rulesetVersion, success);
      }
    }
  }

  private void initOrUpdateWafHandle(AppSecModuleConfigurer.Reconfiguration reconf)
      throws AppSecModuleActivationException {
    CtxAndAddresses prevContextAndAddresses = this.ctxAndAddresses.get();
    WafHandle newHandle;
    try {
      newHandle = wafBuilder.buildWafHandleInstance();
    } catch (AbstractWafException e) {
      throw new AppSecModuleActivationException(
          "Could not initialize waf handle, no rules were added!", e);
    }

    Collection<Address<?>> addresses = getUsedAddresses(newHandle);
    CtxAndAddresses newContextAndAddresses = new CtxAndAddresses(addresses, newHandle);
    if (!this.ctxAndAddresses.compareAndSet(prevContextAndAddresses, newContextAndAddresses)) {
      newHandle.close();
      throw new AppSecModuleActivationException("Concurrent update of WAF configuration");
    }

    if (prevContextAndAddresses != null) {
      prevContextAndAddresses.ctx.close();
    }

    reconf.reloadSubscriptions();
  }

  private static RateLimiter getRateLimiter(Monitoring monitoring) {
    if (monitoring == null) {
      return null;
    }
    RateLimiter rateLimiter = null;
    int appSecTraceRateLimit = Config.get().getAppSecTraceRateLimit();
    if (appSecTraceRateLimit > 0) {
      Counter counter = monitoring.newCounter("_dd.java.appsec.rate_limit.dropped_traces");
      rateLimiter =
          new RateLimiter(
              appSecTraceRateLimit, SystemTimeSource.INSTANCE, () -> counter.increment(1));
    }
    return rateLimiter;
  }

  @Override
  public void setRuleVersion(String rulesetVersion) {
    this.rulesetVersion = rulesetVersion;
  }

  @Override
  public String getName() {
    return "ddwaf";
  }

  @Override
  public String getInfo() {
    CtxAndAddresses ctxAndAddresses = this.ctxAndAddresses.get();
    if (ctxAndAddresses == null) {
      return "ddwaf(libddwaf: " + Waf.LIB_VERSION + ") no rules loaded";
    }

    return "ddwaf(libddwaf: " + Waf.LIB_VERSION + ") loaded";
  }

  @Override
  public Collection<DataSubscription> getDataSubscriptions() {
    if (this.ctxAndAddresses.get() == null) {
      return Collections.emptyList();
    }
    return singletonList(new WAFDataCallback());
  }

  @Override
  public boolean isWafBuilderSet() {
    return wafBuilder != null;
  }

  private static Collection<Address<?>> getUsedAddresses(WafHandle wafHandle) {
    String[] usedAddresses = wafHandle.getKnownAddresses();
    Set<Address<?>> addressList = new HashSet<>(usedAddresses.length);
    for (String addrKey : usedAddresses) {
      Address<?> address = KnownAddresses.forName(addrKey);
      if (address != null) {
        addressList.add(address);
      }
    }
    return addressList;
  }

  private class WAFDataCallback extends DataSubscription {
    public WAFDataCallback() {
      super(ctxAndAddresses.get().addressesOfInterest, Priority.DEFAULT);
    }

    @Override
    public void onDataAvailable(
        ChangeableFlow flow,
        AppSecRequestContext reqCtx,
        DataBundle newData,
        GatewayContext gwCtx) {
      Waf.ResultWithData resultWithData;
      CtxAndAddresses ctxAndAddr = ctxAndAddresses.get();
      if (ctxAndAddr == null) {
        log.debug("Skipped; the WAF is not configured");
        return;
      }
      if (reqCtx.isWafContextClosed()) {
        log.debug("Skipped; the WAF context is closed");
        if (gwCtx.isRasp) {
          WafMetricCollector.get().raspRuleSkipped(gwCtx.raspRuleType);
        }
        return;
      }

      StandardizedLogging.executingWAF(log);
      long start = 0L;
      if (log.isDebugEnabled()) {
        start = System.currentTimeMillis();
      }

      if (gwCtx.isRasp) {
        WafMetricCollector.get().raspRuleEval(gwCtx.raspRuleType);
      }

      try {
        resultWithData = doRunWaf(reqCtx, newData, ctxAndAddr, gwCtx);
      } catch (TimeoutWafException tpe) {
        if (gwCtx.isRasp) {
          reqCtx.increaseRaspTimeouts();
          WafMetricCollector.get().raspTimeout(gwCtx.raspRuleType);
        } else {
          reqCtx.increaseWafTimeouts();
          log.debug(LogCollector.EXCLUDE_TELEMETRY, "Timeout calling the WAF", tpe);
        }
        return;
      } catch (UnclassifiedWafException e) {
        if (!reqCtx.isWafContextClosed()) {
          log.error("Error calling WAF", e);
        }
        incrementErrorCodeMetric(reqCtx, gwCtx, e.code);
        return;
      } catch (AbstractWafException e) {
        incrementErrorCodeMetric(reqCtx, gwCtx, e.code);
        return;
      } finally {
        if (log.isDebugEnabled()) {
          long elapsed = System.currentTimeMillis() - start;
          StandardizedLogging.finishedExecutionWAF(log, elapsed);
        }
        if (!gwCtx.isRasp) {
          WafMetrics wafMetrics = reqCtx.getWafMetrics();
          if (wafMetrics != null) {
            final long stringTooLong = wafMetrics.getTruncatedStringTooLongCount();
            final long listMapTooLarge = wafMetrics.getTruncatedListMapTooLargeCount();
            final long objectTooDeep = wafMetrics.getTruncatedObjectTooDeepCount();

            if (stringTooLong > 0 || listMapTooLarge > 0 || objectTooDeep > 0) {
              reqCtx.setWafTruncated();
              WafMetricCollector.get()
                  .wafInputTruncated(stringTooLong > 0, listMapTooLarge > 0, objectTooDeep > 0);
            }
          }
        }
      }

      StandardizedLogging.inAppWafReturn(log, resultWithData);

      if (resultWithData.result != Waf.Result.OK) {
        if (log.isDebugEnabled()) {
          log.warn("WAF signalled result {}: {}", resultWithData.result, resultWithData.data);
        }

        if (gwCtx.isRasp) {
          reqCtx.setRaspMatched(true);
          WafMetricCollector.get().raspRuleMatch(gwCtx.raspRuleType);
        }

        String securityResponseId = null;
        for (Map.Entry<String, Map<String, Object>> action : resultWithData.actions.entrySet()) {
          String actionType = action.getKey();
          Map<String, Object> actionParams = action.getValue();

          ActionInfo actionInfo = new ActionInfo(actionType, actionParams);

          if ("block_request".equals(actionInfo.type)) {
            // Extract security_response_id from action parameters for use in triggers
            securityResponseId = (String) actionInfo.parameters.get("security_response_id");
            Flow.Action.RequestBlockingAction rba =
                createBlockRequestAction(actionInfo, reqCtx, gwCtx.isRasp, securityResponseId);
            flow.setAction(rba);
          } else if ("redirect_request".equals(actionInfo.type)) {
            // Extract security_response_id from action parameters for use in triggers
            securityResponseId = (String) actionInfo.parameters.get("security_response_id");
            Flow.Action.RequestBlockingAction rba =
                createRedirectRequestAction(actionInfo, reqCtx, gwCtx.isRasp, securityResponseId);
            flow.setAction(rba);
          } else if ("generate_stack".equals(actionInfo.type)) {
            if (Config.get().isAppSecStackTraceEnabled()) {
              String stackId = (String) actionInfo.parameters.get("stack_id");
              StackTraceEvent stackTraceEvent = createExploitStackTraceEvent(stackId);
              reqCtx.reportStackTrace(stackTraceEvent);
            } else {
              log.debug("Ignoring action with type generate_stack (disabled by config)");
            }
          } else if ("extended_data_collection".equals(actionInfo.type)) {
            // Extended data collection is handled by the GatewayBridge
            reqCtx.setExtendedDataCollection(true);
            // Handle max_collected_headers parameter which can come as Number or String
            // representation of a number
            int maxHeaders = AppSecRequestContext.DEFAULT_EXTENDED_DATA_COLLECTION_MAX_HEADERS;
            Object maxHeadersParam =
                actionInfo.parameters.getOrDefault(
                    "max_collected_headers",
                    AppSecRequestContext.DEFAULT_EXTENDED_DATA_COLLECTION_MAX_HEADERS);
            if (maxHeadersParam instanceof Number) {
              maxHeaders = ((Number) maxHeadersParam).intValue();
            } else if (maxHeadersParam instanceof String) {
              try {
                maxHeaders = Integer.parseInt((String) maxHeadersParam);
              } catch (NumberFormatException e) {
                log.debug("Failed to parse max_collected_headers value: {}", maxHeadersParam);
              }
            }
            reqCtx.setExtendedDataCollectionMaxHeaders(maxHeaders);
          } else {
            log.info("Ignoring action with type {}", actionInfo.type);
            if (!gwCtx.isRasp) {
              reqCtx.setWafRequestBlockFailure();
            }
          }
        }
        Collection<AppSecEvent> events = buildEvents(resultWithData, securityResponseId);
        boolean isThrottled = reqCtx.isThrottled(rateLimiter);

        if (!isThrottled) {
          if (resultWithData.keep) {
            reqCtx.setManuallyKept(true);
            AgentSpan activeSpan = AgentTracer.get().activeSpan();
            if (activeSpan != null) {
              log.debug("Setting force-keep tag and manual keep tag on the current span");
              // Keep event related span, because it could be ignored in case of
              // reduced datadog sampling rate.
              activeSpan.getLocalRootSpan().setTag(Tags.ASM_KEEP, true);
              // If APM is disabled, inform downstream services that the current
              // distributed trace contains at least one ASM event and must inherit
              // the given force-keep priority
              activeSpan
                  .getLocalRootSpan()
                  .setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
            }
          } else {
            // If active span is not available then we need to set manual keep in GatewayBridge
            log.debug("There is no active span available");
          }
        } else {
          log.debug("Rate limited WAF events");
          if (!gwCtx.isRasp) {
            reqCtx.setWafRateLimited();
          }
        }

        if (flow.isBlocking()) {
          if (!gwCtx.isRasp) {
            reqCtx.setWafBlocked();
          }
        }
        // report is still done even without keep, in case sampler_keep is desired
        if (resultWithData.events) {
          reqCtx.reportEvents(events);
        }
      }

      if (resultWithData.attributes != null && !resultWithData.attributes.isEmpty()) {
        reqCtx.reportDerivatives(resultWithData.attributes);
      }
    }

    private Flow.Action.RequestBlockingAction createBlockRequestAction(
        final ActionInfo actionInfo,
        final AppSecRequestContext reqCtx,
        final boolean isRasp,
        final String securityResponseId) {
      try {
        int statusCode;
        Object statusCodeObj = actionInfo.parameters.get("status_code");
        if (statusCodeObj instanceof Number) {
          statusCode = ((Number) statusCodeObj).intValue();
        } else if (statusCodeObj instanceof String) {
          statusCode = Integer.parseInt((String) statusCodeObj);
        } else {
          statusCode = 403;
        }
        String contentType = (String) actionInfo.parameters.getOrDefault("type", "auto");
        BlockingContentType blockingContentType = BlockingContentType.AUTO;
        try {
          blockingContentType = BlockingContentType.valueOf(contentType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException iae) {
          log.warn("Unknown content type: {}; using auto", contentType);
        }
        return new Flow.Action.RequestBlockingAction(
            statusCode, blockingContentType, Collections.emptyMap(), securityResponseId);
      } catch (RuntimeException cce) {
        log.warn("Invalid blocking action data", cce);
        if (!isRasp) {
          reqCtx.setWafRequestBlockFailure();
        }
        return null;
      }
    }

    private Flow.Action.RequestBlockingAction createRedirectRequestAction(
        final ActionInfo actionInfo,
        final AppSecRequestContext reqCtx,
        final boolean isRasp,
        final String securityResponseId) {
      try {
        int statusCode;
        Object statusCodeObj = actionInfo.parameters.get("status_code");
        if (statusCodeObj instanceof Number) {
          statusCode = ((Number) statusCodeObj).intValue();
        } else if (statusCodeObj instanceof String) {
          statusCode = Integer.parseInt((String) statusCodeObj);
        } else {
          statusCode = 303;
        }
        if (statusCode < 300 || statusCode > 399) {
          statusCode = 303;
        }
        String location = (String) actionInfo.parameters.get("location");
        if (location == null) {
          throw new RuntimeException("redirect_request action has no location");
        }
        if (securityResponseId != null && !securityResponseId.isEmpty()) {
          // For custom redirects, only replace [security_response_id] placeholder if present in the
          // URL.
          // The client decides whether to include security_response_id by adding the placeholder.
          // We don't automatically append security_response_id as a URL parameter.
          if (location.contains("[security_response_id]")) {
            location = location.replace("[security_response_id]", securityResponseId);
          }
        }
        return Flow.Action.RequestBlockingAction.forRedirect(
            statusCode, location, securityResponseId);
      } catch (RuntimeException cce) {
        log.warn("Invalid blocking action data", cce);
        if (!isRasp) {
          reqCtx.setWafRequestBlockFailure();
        }
        return null;
      }
    }

    private StackTraceEvent createExploitStackTraceEvent(String stackId) {
      if (stackId == null || stackId.isEmpty()) {
        return null;
      }
      List<StackTraceFrame> result = StackUtils.generateUserCodeStackTrace();
      return new StackTraceEvent(result, DEFAULT_LANGUAGE, stackId, EXPLOIT_DETECTED_MSG);
    }

    private Waf.ResultWithData doRunWaf(
        AppSecRequestContext reqCtx,
        DataBundle newData,
        CtxAndAddresses ctxAndAddr,
        GatewayContext gwCtx)
        throws AbstractWafException {
      WafContext wafContext =
          reqCtx.getOrCreateWafContext(ctxAndAddr.ctx, wafMetricsEnabled, gwCtx.isRasp);
      WafMetrics metrics;
      if (gwCtx.isRasp) {
        metrics = reqCtx.getRaspMetrics();
        reqCtx.getRaspMetricsCounter().incrementAndGet();
      } else {
        metrics = reqCtx.getWafMetrics();
      }

      if (gwCtx.isTransient) {
        return runWafTransient(wafContext, metrics, newData, ctxAndAddr);
      } else {
        return runWafContext(wafContext, metrics, newData, ctxAndAddr);
      }
    }

    private Waf.ResultWithData runWafContext(
        WafContext wafContext, WafMetrics metrics, DataBundle newData, CtxAndAddresses ctxAndAddr)
        throws AbstractWafException {
      return wafContext.run(
          new DataBundleMapWrapper(ctxAndAddr.addressesOfInterest, newData), LIMITS, metrics);
    }
  }

  private static void incrementErrorCodeMetric(
      AppSecRequestContext reqCtx, GatewayContext gwCtx, int code) {
    if (gwCtx.isRasp) {
      WafMetricCollector.get().raspErrorCode(gwCtx.raspRuleType, code);
    } else {
      WafMetricCollector.get().wafErrorCode(code);
      reqCtx.setWafErrors();
    }
  }

  private Waf.ResultWithData runWafTransient(
      WafContext wafContext, WafMetrics metrics, DataBundle newData, CtxAndAddresses ctxAndAddr)
      throws AbstractWafException {
    return wafContext.runEphemeral(
        new DataBundleMapWrapper(ctxAndAddr.addressesOfInterest, newData), LIMITS, metrics);
  }

  private Collection<AppSecEvent> buildEvents(
      Waf.ResultWithData actionWithData, String securityResponseId) {
    if (actionWithData.data == null) {
      log.debug(SEND_TELEMETRY, "WAF result data is null");
      return Collections.emptyList();
    }
    Collection<WAFResultData> listResults;
    try {
      if (actionWithData.data == null || actionWithData.data.isEmpty()) {
        log.debug("WAF returned no data");
        return emptyList();
      }
      listResults = RES_JSON_ADAPTER.fromJson(actionWithData.data);
    } catch (IOException e) {
      throw new UndeclaredThrowableException(e);
    }

    if (listResults != null && !listResults.isEmpty()) {
      return listResults.stream()
          .map(wafResult -> buildEvent(wafResult, securityResponseId))
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }
    return emptyList();
  }

  private AppSecEvent buildEvent(WAFResultData wafResult, String securityResponseId) {

    if (wafResult == null || wafResult.rule == null || wafResult.rule_matches == null) {
      log.warn("WAF result is empty: {}", wafResult);
      return null;
    }

    Long spanId = null;
    AgentSpan agentSpan = AgentTracer.get().activeSpan();
    if (agentSpan != null) {
      spanId = agentSpan.getSpanId();
    }

    return new AppSecEvent.Builder()
        .withRule(wafResult.rule)
        .withRuleMatches(wafResult.rule_matches)
        .withSpanId(spanId)
        .withStackId(wafResult.stack_id)
        .withSecurityResponseId(securityResponseId)
        .build();
  }

  private static final class DataBundleMapWrapper implements Map<String, Object> {
    private final Collection<Address<?>> addressesOfInterest;
    private final DataBundle dataBundle;

    private DataBundleMapWrapper(
        Collection<Address<?>> addressesOfInterest, DataBundle dataBundle) {
      this.addressesOfInterest = addressesOfInterest;
      this.dataBundle = dataBundle;
    }

    // ddwaf only calls entrySet().iterator() and size()
    @Nonnull
    @Override
    public Set<Entry<String, Object>> entrySet() {
      try {
        return (Set<Entry<String, Object>>)
            PROXY_CLASS_CONSTRUCTOR.newInstance(new SetIteratorInvocationHandler());
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        throw new UndeclaredThrowableException(e);
      }
    }

    private class SetIteratorInvocationHandler implements InvocationHandler {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        if (!method.getName().equals("iterator")) {
          throw new UnsupportedOperationException(
              "Only supported method is 'iterator'; got " + method.getName());
        }

        final Iterator<Address<?>> addrIterator = dataBundle.getAllAddresses().iterator();
        final MutableEntry entry = new MutableEntry();

        return new Iterator<Entry<String, Object>>() {
          private Address<?> next = computeNextAddress();

          private Address<?> computeNextAddress() {
            return addrIterator.hasNext() ? addrIterator.next() : null;
          }

          @Override
          public boolean hasNext() {
            return next != null;
          }

          @Override
          public Entry<String, Object> next() {
            if (next == null) {
              throw new NoSuchElementException();
            }
            // the usage pattern in ddwaf allows object recycling here
            entry.key = next.getKey();
            entry.value =
                addressesOfInterest.contains(next) ? dataBundle.get(next) : Collections.emptyMap();
            next = computeNextAddress();
            return entry;
          }
        };
      }
    }

    @Override
    public int size() {
      return dataBundle.size();
    }

    /* unimplemented map methods */
    @Override
    public boolean isEmpty() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsKey(Object key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsValue(Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object get(Object key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object put(String key, Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@Nonnull Map<? extends String, ?> m) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> keySet() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Object> values() {
      throw new UnsupportedOperationException();
    }
  }

  private static final class MutableEntry implements Map.Entry<String, Object> {
    String key;
    Object value;

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public Object setValue(Object value) {
      throw new UnsupportedOperationException();
    }
  }
}
