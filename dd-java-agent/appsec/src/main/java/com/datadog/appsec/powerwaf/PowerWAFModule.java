package com.datadog.appsec.powerwaf;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toMap;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.config.CurrentAppSecConfig;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.datadog.appsec.report.raw.events.Parameter;
import com.datadog.appsec.report.raw.events.Rule;
import com.datadog.appsec.report.raw.events.RuleMatch;
import com.datadog.appsec.report.raw.events.Tags;
import com.datadog.appsec.util.StandardizedLogging;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.telemetry.WafMetricCollector;
import io.sqreen.powerwaf.Additive;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafConfig;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.PowerwafMetrics;
import io.sqreen.powerwaf.RuleSetInfo;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import io.sqreen.powerwaf.exception.InvalidRuleSetException;
import io.sqreen.powerwaf.exception.TimeoutPowerwafException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PowerWAFModule implements AppSecModule {
  private static final Logger log = LoggerFactory.getLogger(PowerWAFModule.class);

  private static final int MAX_DEPTH = 10;
  private static final int MAX_ELEMENTS = 150;
  private static final int MAX_STRING_SIZE = 4096;
  private static volatile Powerwaf.Limits LIMITS;
  private static final Class<?> PROXY_CLASS =
      Proxy.getProxyClass(PowerWAFModule.class.getClassLoader(), Set.class);
  private static final Constructor<?> PROXY_CLASS_CONSTRUCTOR;

  private static final JsonAdapter<List<PowerWAFResultData>> RES_JSON_ADAPTER;

  private static final Map<String, ActionInfo> DEFAULT_ACTIONS;

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
    final PowerwafContext ctx;
    final Map<String /* id */, ActionInfo> actionInfoMap;

    private CtxAndAddresses(
        Collection<Address<?>> addressesOfInterest,
        PowerwafContext ctx,
        Map<String, ActionInfo> actionInfoMap) {
      this.addressesOfInterest = addressesOfInterest;
      this.ctx = ctx;
      this.actionInfoMap = actionInfoMap;
    }

    CtxAndAddresses withNewActions(Map<String, ActionInfo> actionInfoMap) {
      return new CtxAndAddresses(this.addressesOfInterest, this.ctx, actionInfoMap);
    }
  }

  static {
    try {
      PROXY_CLASS_CONSTRUCTOR = PROXY_CLASS.getConstructor(InvocationHandler.class);
    } catch (NoSuchMethodException e) {
      throw new UndeclaredThrowableException(e);
    }

    Moshi moshi = new Moshi.Builder().build();
    RES_JSON_ADAPTER =
        moshi.adapter(Types.newParameterizedType(List.class, PowerWAFResultData.class));

    Map<String, Object> actionParams = new HashMap<>();
    actionParams.put("status_code", 403);
    actionParams.put("type", "auto");
    actionParams.put("grpc_status_code", 10);
    DEFAULT_ACTIONS =
        Collections.singletonMap("block", new ActionInfo("block_request", actionParams));
    createLimitsObject();
  }

  // used in testing
  static void createLimitsObject() {
    LIMITS =
        new Powerwaf.Limits(
            MAX_DEPTH,
            MAX_ELEMENTS,
            MAX_STRING_SIZE,
            /* set effectively infinite budgets. Don't use Long.MAX_VALUE, because
             * traditionally powerwaf has had problems with too large budgets */
            ((long) Integer.MAX_VALUE) * 1000,
            Config.get().getAppSecWafTimeout());
  }

  private final boolean wafMetricsEnabled =
      Config.get().isAppSecWafMetrics(); // could be static if not for tests
  private final AtomicReference<CtxAndAddresses> ctxAndAddresses = new AtomicReference<>();
  private final PowerWAFInitializationResultReporter initReporter =
      new PowerWAFInitializationResultReporter();
  private final PowerWAFStatsReporter statsReporter = new PowerWAFStatsReporter();

  private String currentRulesVersion;

  @Override
  public void config(AppSecModuleConfigurer appSecConfigService)
      throws AppSecModuleActivationException {

    Optional<Object> initialConfig =
        appSecConfigService.addSubConfigListener("waf", this::applyConfig);

    ProductActivation appSecEnabledConfig = Config.get().getAppSecActivation();
    if (appSecEnabledConfig == ProductActivation.FULLY_ENABLED) {
      if (!initialConfig.isPresent()) {
        throw new AppSecModuleActivationException("No initial config for WAF");
      }

      try {
        applyConfig(initialConfig.get(), AppSecModuleConfigurer.Reconfiguration.NOOP);
      } catch (ClassCastException e) {
        throw new AppSecModuleActivationException("Config expected to be CurrentAppSecConfig", e);
      }
    }

    appSecConfigService.addTraceSegmentPostProcessor(initReporter);
    if (wafMetricsEnabled) {
      appSecConfigService.addTraceSegmentPostProcessor(statsReporter);
    }
  }

  // this function is called from one thread in the beginning that's different
  // from the RC thread that calls it later on
  private void applyConfig(Object config_, AppSecModuleConfigurer.Reconfiguration reconf)
      throws AppSecModuleActivationException {
    log.debug("Configuring WAF");

    CurrentAppSecConfig config = (CurrentAppSecConfig) config_;

    CtxAndAddresses curCtxAndAddresses = this.ctxAndAddresses.get();

    if (!LibSqreenInitialization.ONLINE) {
      throw new AppSecModuleActivationException(
          "In-app WAF initialization failed. See previous log entries");
    }

    if (curCtxAndAddresses == null) {
      config.dirtyStatus.markAllDirty();
    }

    try {
      if (config.dirtyStatus.isDirtyForDdwafUpdate()) {
        // ddwaf_init/update
        initializeNewWafCtx(reconf, config, curCtxAndAddresses);
      } else if (config.dirtyStatus.isDirtyForActions()) {
        // only internal actions change
        // if we're here curCtxAndAddresses is not null
        Map<String, ActionInfo> actionInfoMap =
            calculateEffectiveActions(curCtxAndAddresses, config.getMergedUpdateConfig());
        CtxAndAddresses newCtxAndAddresses = curCtxAndAddresses.withNewActions(actionInfoMap);
        boolean success =
            this.ctxAndAddresses.compareAndSet(curCtxAndAddresses, newCtxAndAddresses);
        if (!success) {
          throw new AppSecModuleActivationException("Concurrent update of WAF configuration");
        }
      }
    } catch (Exception e) {
      throw new AppSecModuleActivationException("Could not initialize/update waf", e);
    }
  }

  private void initializeNewWafCtx(
      AppSecModuleConfigurer.Reconfiguration reconf,
      CurrentAppSecConfig config,
      CtxAndAddresses prevContextAndAddresses)
      throws AppSecModuleActivationException, IOException {
    CtxAndAddresses newContextAndAddresses;
    RuleSetInfo initReport = null;

    AppSecConfig ruleConfig = config.getMergedUpdateConfig();
    PowerwafContext newPwafCtx = null;
    try {
      String uniqueId = UUID.randomUUID().toString();

      if (prevContextAndAddresses == null) {
        PowerwafConfig pwConfig = createPowerwafConfig();
        newPwafCtx = Powerwaf.createContext(uniqueId, pwConfig, ruleConfig.getRawConfig());
      } else {
        newPwafCtx = prevContextAndAddresses.ctx.update(uniqueId, ruleConfig.getRawConfig());
      }

      initReport = newPwafCtx.getRuleSetInfo();
      Collection<Address<?>> addresses = getUsedAddresses(newPwafCtx);

      // Update current rules' version if you need
      if (initReport != null && initReport.rulesetVersion != null) {
        currentRulesVersion = initReport.rulesetVersion;
      }

      if (prevContextAndAddresses == null) {
        WafMetricCollector.get().wafInit(Powerwaf.LIB_VERSION, currentRulesVersion);
      } else {
        WafMetricCollector.get().wafUpdates(currentRulesVersion);
      }

      if (initReport != null) {
        log.info(
            "Created {} WAF context with rules ({} OK, {} BAD), version {}",
            prevContextAndAddresses == null ? "new" : "updated",
            initReport.getNumRulesOK(),
            initReport.getNumRulesError(),
            initReport.rulesetVersion);
      } else {
        log.warn(
            "Created {} WAF context without rules",
            prevContextAndAddresses == null ? "new" : "updated");
      }

      Map<String, ActionInfo> actionInfoMap =
          calculateEffectiveActions(prevContextAndAddresses, ruleConfig);

      newContextAndAddresses = new CtxAndAddresses(addresses, newPwafCtx, actionInfoMap);
      if (initReport != null) {
        this.statsReporter.rulesVersion = initReport.rulesetVersion;
      }
    } catch (InvalidRuleSetException irse) {
      initReport = irse.ruleSetInfo;
      throw new AppSecModuleActivationException("Error creating WAF rules", irse);
    } catch (RuntimeException | AbstractPowerwafException e) {
      if (newPwafCtx != null) {
        newPwafCtx.close();
      }
      throw new AppSecModuleActivationException("Error creating WAF rules", e);
    } finally {
      if (initReport != null) {
        this.initReporter.setReportForPublication(initReport);
      }
    }

    if (!this.ctxAndAddresses.compareAndSet(prevContextAndAddresses, newContextAndAddresses)) {
      newPwafCtx.close();
      throw new AppSecModuleActivationException("Concurrent update of WAF configuration");
    }

    if (prevContextAndAddresses != null) {
      prevContextAndAddresses.ctx.close();
    }

    reconf.reloadSubscriptions();
  }

  private Map<String, ActionInfo> calculateEffectiveActions(
      CtxAndAddresses prevContextAndAddresses, AppSecConfig ruleConfig) {
    Map<String, ActionInfo> actionInfoMap;
    List<Map<String, Object>> actions =
        (List<Map<String, Object>>) ruleConfig.getRawConfig().get("actions");
    if (actions == null) {
      if (prevContextAndAddresses == null) {
        // brand-new context; no actions provided: use default ones
        actionInfoMap = DEFAULT_ACTIONS;
      } else {
        // in update, no changed actions; keep the old one
        actionInfoMap = prevContextAndAddresses.actionInfoMap;
      }
    } else {
      // actions were updated
      actionInfoMap = new HashMap<>(DEFAULT_ACTIONS);
      actionInfoMap.putAll(
          ((List<Map<String, Object>>)
                  ruleConfig.getRawConfig().getOrDefault("actions", Collections.emptyList()))
              .stream()
                  .collect(
                      toMap(
                          m -> (String) m.get("id"),
                          m ->
                              new ActionInfo(
                                  (String) m.get("type"),
                                  (Map<String, Object>) m.get("parameters")))));
    }
    return actionInfoMap;
  }

  private PowerwafConfig createPowerwafConfig() {
    PowerwafConfig pwConfig = new PowerwafConfig();
    Config config = Config.get();
    String keyRegexp = config.getAppSecObfuscationParameterKeyRegexp();
    if (keyRegexp != null) {
      pwConfig.obfuscatorKeyRegex = keyRegexp;
    }
    String valueRegexp = config.getAppSecObfuscationParameterValueRegexp();
    if (valueRegexp != null) {
      pwConfig.obfuscatorValueRegex = valueRegexp;
    }
    return pwConfig;
  }

  @Override
  public String getName() {
    return "powerwaf";
  }

  @Override
  public String getInfo() {
    CtxAndAddresses ctxAndAddresses = this.ctxAndAddresses.get();
    if (ctxAndAddresses == null) {
      return "powerwaf(libddwaf: " + Powerwaf.LIB_VERSION + ") no rules loaded";
    }

    return "powerwaf(libddwaf: " + Powerwaf.LIB_VERSION + ") loaded";
  }

  @Override
  public Collection<DataSubscription> getDataSubscriptions() {
    if (this.ctxAndAddresses.get() == null) {
      return Collections.emptyList();
    }
    return singletonList(new PowerWAFDataCallback());
  }

  private static Collection<Address<?>> getUsedAddresses(PowerwafContext ctx) {
    String[] usedAddresses = ctx.getUsedAddresses();
    List<Address<?>> addressList = new ArrayList<>(usedAddresses.length);
    for (String addrKey : usedAddresses) {
      Address<?> address = KnownAddresses.forName(addrKey);
      if (address != null) {
        addressList.add(address);
      } else {
        log.warn("WAF has rule against unknown address {}", addrKey);
      }
    }
    return addressList;
  }

  private class PowerWAFDataCallback extends DataSubscription {
    public PowerWAFDataCallback() {
      super(ctxAndAddresses.get().addressesOfInterest, Priority.DEFAULT);
    }

    @Override
    public void onDataAvailable(
        ChangeableFlow flow, AppSecRequestContext reqCtx, DataBundle newData, boolean isTransient) {
      Powerwaf.ResultWithData resultWithData;
      CtxAndAddresses ctxAndAddr = ctxAndAddresses.get();
      if (ctxAndAddr == null) {
        log.debug("Skipped; the WAF is not configured");
        return;
      }

      StandardizedLogging.executingWAF(log);
      long start = 0L;
      if (log.isDebugEnabled()) {
        start = System.currentTimeMillis();
      }

      try {
        resultWithData = doRunPowerwaf(reqCtx, newData, ctxAndAddr, isTransient);
      } catch (TimeoutPowerwafException tpe) {
        log.debug("Timeout calling the WAF", tpe);
        return;
      } catch (AbstractPowerwafException e) {
        log.error("Error calling WAF", e);
        return;
      } finally {
        if (log.isDebugEnabled()) {
          long elapsed = System.currentTimeMillis() - start;
          StandardizedLogging.finishedExecutionWAF(log, elapsed);
        }
      }

      StandardizedLogging.inAppWafReturn(log, resultWithData);

      if (resultWithData.result != Powerwaf.Result.OK) {
        if (log.isDebugEnabled()) {
          log.warn("WAF signalled result {}: {}", resultWithData.result, resultWithData.data);
        }

        for (String action : resultWithData.actions) {
          ActionInfo actionInfo = ctxAndAddr.actionInfoMap.get(action);
          if (actionInfo == null) {
            log.warn(
                "WAF indicated action {}, but such action id is unknown (not one from {})",
                action,
                ctxAndAddr.actionInfoMap.keySet());
          } else if ("block_request".equals(actionInfo.type)) {
            Flow.Action.RequestBlockingAction rba = createBlockRequestAction(actionInfo);
            flow.setAction(rba);
            break;
          } else if ("redirect_request".equals(actionInfo.type)) {
            Flow.Action.RequestBlockingAction rba = createRedirectRequestAction(actionInfo);
            flow.setAction(rba);
            break;
          } else {
            log.info("Ignoring action with type {}", actionInfo.type);
          }
        }
        Collection<AppSecEvent100> events = buildEvents(resultWithData);
        reqCtx.reportEvents(events);

        if (flow.isBlocking()) {
          WafMetricCollector.get().wafRequestBlocked();
        } else {
          WafMetricCollector.get().wafRequestTriggered();
        }

      } else {
        WafMetricCollector.get().wafRequest();
      }
    }

    private Flow.Action.RequestBlockingAction createBlockRequestAction(ActionInfo actionInfo) {
      try {
        int statusCode =
            ((Number) actionInfo.parameters.getOrDefault("status_code", 403)).intValue();
        String contentType = (String) actionInfo.parameters.getOrDefault("type", "auto");
        BlockingContentType blockingContentType = BlockingContentType.AUTO;
        try {
          blockingContentType = BlockingContentType.valueOf(contentType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException iae) {
          log.warn("Unknown content type: {}; using auto", contentType);
        }
        return new Flow.Action.RequestBlockingAction(statusCode, blockingContentType);
      } catch (RuntimeException cce) {
        log.warn("Invalid blocking action data", cce);
        return null;
      }
    }

    private Flow.Action.RequestBlockingAction createRedirectRequestAction(ActionInfo actionInfo) {
      try {
        int statusCode =
            ((Number) actionInfo.parameters.getOrDefault("status_code", 303)).intValue();
        if (statusCode < 300 || statusCode > 399) {
          statusCode = 303;
        }
        String location = (String) actionInfo.parameters.get("location");
        if (location == null) {
          throw new RuntimeException("redirect_request action has no location");
        }
        return Flow.Action.RequestBlockingAction.forRedirect(statusCode, location);
      } catch (RuntimeException cce) {
        log.warn("Invalid blocking action data", cce);
        return null;
      }
    }

    private Powerwaf.ResultWithData doRunPowerwaf(
        AppSecRequestContext reqCtx,
        DataBundle newData,
        CtxAndAddresses ctxAndAddr,
        boolean isTransient)
        throws AbstractPowerwafException {

      Additive additive = reqCtx.getOrCreateAdditive(ctxAndAddr.ctx, wafMetricsEnabled);
      PowerwafMetrics metrics = reqCtx.getWafMetrics();

      if (isTransient) {
        DataBundle bundle = DataBundle.unionOf(newData, reqCtx);
        return runPowerwafTransient(metrics, bundle, ctxAndAddr);
      } else {
        return runPowerwafAdditive(additive, metrics, newData, ctxAndAddr);
      }
    }

    private Powerwaf.ResultWithData runPowerwafAdditive(
        Additive additive, PowerwafMetrics metrics, DataBundle newData, CtxAndAddresses ctxAndAddr)
        throws AbstractPowerwafException {
      return additive.run(
          new DataBundleMapWrapper(ctxAndAddr.addressesOfInterest, newData), LIMITS, metrics);
    }
  }

  private Powerwaf.ResultWithData runPowerwafTransient(
      PowerwafMetrics metrics, DataBundle bundle, CtxAndAddresses ctxAndAddr)
      throws AbstractPowerwafException {
    return ctxAndAddr.ctx.runRules(
        new DataBundleMapWrapper(ctxAndAddr.addressesOfInterest, bundle), LIMITS, metrics);
  }

  private Collection<AppSecEvent100> buildEvents(Powerwaf.ResultWithData actionWithData) {
    Collection<PowerWAFResultData> listResults;
    try {
      listResults = RES_JSON_ADAPTER.fromJson(actionWithData.data);
    } catch (IOException e) {
      throw new UndeclaredThrowableException(e);
    }

    if (listResults != null && !listResults.isEmpty()) {
      return listResults.stream()
          .map(this::buildEvent)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }
    return emptyList();
  }

  private AppSecEvent100 buildEvent(PowerWAFResultData wafResult) {

    if (wafResult == null || wafResult.rule == null || wafResult.rule_matches == null) {
      log.warn("WAF result is empty: {}", wafResult);
      return null;
    }

    List<RuleMatch> ruleMatchList = new ArrayList<>();
    for (PowerWAFResultData.RuleMatch rule_match : wafResult.rule_matches) {

      List<Parameter> parameterList = new ArrayList<>();

      for (PowerWAFResultData.Parameter parameter : rule_match.parameters) {
        parameterList.add(
            new Parameter.ParameterBuilder()
                .withAddress(parameter.address)
                .withKeyPath(parameter.key_path)
                .withValue(parameter.value)
                .withHighlight(parameter.highlight)
                .build());
      }

      RuleMatch ruleMatch =
          new RuleMatch.RuleMatchBuilder()
              .withOperator(rule_match.operator)
              .withOperatorValue(rule_match.operator_value)
              .withParameters(parameterList)
              .build();

      ruleMatchList.add(ruleMatch);
    }

    return new AppSecEvent100.AppSecEvent100Builder()
        .withRule(
            new Rule.RuleBuilder()
                .withId(wafResult.rule.id)
                .withName(wafResult.rule.name)
                .withTags(
                    new Tags.TagsBuilder()
                        .withType(wafResult.rule.tags.get("type"))
                        .withCategory(wafResult.rule.tags.get("category"))
                        .build())
                .build())
        .withRuleMatches(ruleMatchList)
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

    // powerwaf only calls entrySet().iterator() and size()
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
            // the usage pattern in powerwaf allows object recycling here
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
