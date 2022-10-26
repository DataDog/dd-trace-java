package com.datadog.appsec.powerwaf;

import static java.util.Collections.*;
import static java.util.stream.Collectors.toMap;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.events.*;
import com.datadog.appsec.util.StandardizedLogging;
import com.google.auto.service.AutoService;
import com.squareup.moshi.*;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.Flow;
import io.sqreen.powerwaf.Additive;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafConfig;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.PowerwafMetrics;
import io.sqreen.powerwaf.RuleSetInfo;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import io.sqreen.powerwaf.exception.InvalidRuleSetException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(AppSecModule.class)
public class PowerWAFModule implements AppSecModule {
  private static final Logger log = LoggerFactory.getLogger(PowerWAFModule.class);

  private static final int MAX_DEPTH = 10;
  private static final int MAX_ELEMENTS = 150;
  private static final int MAX_STRING_SIZE = 4096;
  private static final Powerwaf.Limits LIMITS =
      new Powerwaf.Limits(
          MAX_DEPTH,
          MAX_ELEMENTS,
          MAX_STRING_SIZE,
          /* set effectively infinite budgets. Don't use Long.MAX_VALUE, because
           * traditionally powerwaf has had problems with too large budgets */
          ((long) Integer.MAX_VALUE) * 1000,
          ((long) Integer.MAX_VALUE) * 1000);
  private static final Class<?> PROXY_CLASS =
      Proxy.getProxyClass(PowerWAFModule.class.getClassLoader(), Set.class);
  private static final Constructor<?> PROXY_CLASS_CONSTRUCTOR;
  private static final Set<EventType> EVENTS_OF_INTEREST;

  private static final JsonAdapter<List<PowerWAFResultData>> RES_JSON_ADAPTER;

  private static final Map<String, ActionInfo> DEFAULT_ACTIONS;

  private static class RuleInfo {
    final String name;
    final String type;
    final Map<String, String> tags;

    RuleInfo(AppSecConfig.Rule rule) {
      this.name = rule.getName();
      Map<String, String> tags = rule.getTags();
      if (tags != null) {
        this.type = tags.getOrDefault("type", "waf");
        this.tags = tags;
      } else {
        this.type = "invalid";
        this.tags = Collections.emptyMap();
      }
    }
  }

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
    final Map<String, RuleInfo> rulesInfoMap;
    final Map<String /* id */, ActionInfo> actionInfoMap;

    private CtxAndAddresses(
        Collection<Address<?>> addressesOfInterest,
        PowerwafContext ctx,
        Map<String, RuleInfo> rulesInfoMap,
        Map<String, ActionInfo> actionInfoMap) {
      this.addressesOfInterest = addressesOfInterest;
      this.ctx = ctx;
      this.rulesInfoMap = rulesInfoMap;
      this.actionInfoMap = actionInfoMap;
    }
  }

  static {
    try {
      PROXY_CLASS_CONSTRUCTOR = PROXY_CLASS.getConstructor(InvocationHandler.class);
    } catch (NoSuchMethodException e) {
      throw new UndeclaredThrowableException(e);
    }

    EVENTS_OF_INTEREST = new HashSet<>();
    EVENTS_OF_INTEREST.add(EventType.REQUEST_START);
    EVENTS_OF_INTEREST.add(EventType.REQUEST_END);

    Moshi moshi = new Moshi.Builder().build();
    RES_JSON_ADAPTER =
        moshi.adapter(Types.newParameterizedType(List.class, PowerWAFResultData.class));

    Map<String, Object> actionParams = new HashMap<>();
    actionParams.put("status_code", 403);
    actionParams.put("type", "auto");
    actionParams.put("grpc_status_code", 10);
    DEFAULT_ACTIONS =
        Collections.singletonMap("block", new ActionInfo("block_request", actionParams));
  }

  private final boolean wafMetricsEnabled =
      Config.get().isAppSecWafMetrics(); // could be static if not for tests
  private final AtomicReference<CtxAndAddresses> ctxAndAddresses = new AtomicReference<>();
  private AtomicReference<List<Map<String, Object>>> wafData =
      new AtomicReference<>(Collections.emptyList());
  private final AtomicReference<Map<String, Boolean>> wafRulesOverride =
      new AtomicReference<>(Collections.emptyMap());
  private final PowerWAFInitializationResultReporter initReporter =
      new PowerWAFInitializationResultReporter();
  private final PowerWAFStatsReporter statsReporter = new PowerWAFStatsReporter();

  @Override
  public void config(AppSecModuleConfigurer appSecConfigService)
      throws AppSecModuleActivationException {
    Optional<Object> initialData =
        appSecConfigService.addSubConfigListener("waf_data", this::updateWafData);
    if (initialData.isPresent()) {
      this.wafData.set((List<Map<String, Object>>) initialData.get());
    }
    Optional<Object> initialRuleStatus =
        appSecConfigService.addSubConfigListener(
            "waf_rules_override", this::updateWafRulesOverride);
    if (initialRuleStatus.isPresent()) {
      this.wafRulesOverride.set((Map<String, Boolean>) initialRuleStatus.get());
    }

    Optional<Object> initialConfig =
        appSecConfigService.addSubConfigListener("waf", this::applyConfig);
    if (!initialConfig.isPresent()) {
      throw new AppSecModuleActivationException("No initial config for WAF");
    }

    try {
      applyConfig(initialConfig.get(), AppSecModuleConfigurer.Reconfiguration.NOOP);
    } catch (ClassCastException e) {
      throw new AppSecModuleActivationException("Config expected to be AppSecConfig", e);
    }

    appSecConfigService.addTraceSegmentPostProcessor(initReporter);
    if (wafMetricsEnabled) {
      appSecConfigService.addTraceSegmentPostProcessor(statsReporter);
    }
  }

  // after the initial config, changes in ctxAndAddresses and wafData
  // should happen on the same thread (the remote config thread), so it can't
  // happen that ctxAndAddresses is replaced before we have a chance of
  // updating the rule data.
  // We need to protect against races with the thread doing initial config though
  private void updateWafData(Object data_, AppSecModuleConfigurer.Reconfiguration ignoredReconf) {
    List<Map<String, Object>> data = (List<Map<String, Object>>) data_;

    this.wafData.set(data); // save for reapplying data on future rule updates
    CtxAndAddresses curCtxAndAddr = this.ctxAndAddresses.get();
    if (curCtxAndAddr != null) {
      curCtxAndAddr.ctx.updateRuleData(data);
    }
  }

  private void updateWafRulesOverride(
      Object data_, AppSecModuleConfigurer.Reconfiguration reconfiguration) {
    Map<String, Boolean> data = (Map<String, Boolean>) data_;
    this.wafRulesOverride.set(data);
    CtxAndAddresses curCtxAndAddr = this.ctxAndAddresses.get();
    if (curCtxAndAddr != null) {
      Map<String, Boolean> toggleSpec =
          new FilledInRuleTogglingMap(data, curCtxAndAddr.rulesInfoMap.keySet());
      curCtxAndAddr.ctx.toggleRules(toggleSpec);
    }
  }

  private void applyConfig(Object config_, AppSecModuleConfigurer.Reconfiguration reconf)
      throws AppSecModuleActivationException {
    log.info("Configuring WAF");

    AppSecConfig config = (AppSecConfig) config_;

    CtxAndAddresses prevContextAndAddresses = this.ctxAndAddresses.get();
    CtxAndAddresses newContextAndAddresses;

    if (!LibSqreenInitialization.ONLINE) {
      throw new AppSecModuleActivationException(
          "In-app WAF initialization failed. See previous log entries");
    }

    RuleSetInfo initReport = null;

    PowerwafContext newPwafCtx = null;
    try {
      String uniqueId = UUID.randomUUID().toString();
      PowerwafConfig pwConfig = createPowerwafConfig();
      newPwafCtx = Powerwaf.createContext(uniqueId, pwConfig, config.getRawConfig());

      initReport = newPwafCtx.getRuleSetInfo();
      Collection<Address<?>> addresses = getUsedAddresses(newPwafCtx);

      List<Map<String, Object>> wafData = this.wafData.get();
      if (!wafData.isEmpty()) {
        newPwafCtx.updateRuleData(wafData);
      }

      Map<String, RuleInfo> rulesInfoMap =
          config.getRules().stream().collect(toMap(AppSecConfig.Rule::getId, RuleInfo::new));

      Map<String, ActionInfo> actionInfoMap = new HashMap<>(DEFAULT_ACTIONS);
      actionInfoMap.putAll(
          ((List<Map<String, Object>>)
                  config.getRawConfig().getOrDefault("actions", Collections.emptyList()))
              .stream()
                  .collect(
                      toMap(
                          m -> (String) m.get("id"),
                          m ->
                              new ActionInfo(
                                  (String) m.get("type"),
                                  (Map<String, Object>) m.get("parameters")))));

      Map<String, Boolean> rulesOverride = this.wafRulesOverride.get();
      if (!rulesOverride.isEmpty()) {
        newPwafCtx.toggleRules(new FilledInRuleTogglingMap(rulesOverride, rulesInfoMap.keySet()));
      }

      newContextAndAddresses =
          new CtxAndAddresses(addresses, newPwafCtx, rulesInfoMap, actionInfoMap);
      if (initReport != null) {
        this.statsReporter.rulesVersion = initReport.fileVersion;
      }
    } catch (InvalidRuleSetException irse) {
      initReport = irse.ruleSetInfo;
      throw new AppSecModuleActivationException("Error creating WAF rules", irse);
    } catch (RuntimeException | AbstractPowerwafException e) {
      if (newPwafCtx != null) {
        newPwafCtx.delReference();
      }
      throw new AppSecModuleActivationException("Error creating WAF rules", e);
    } finally {
      if (initReport != null) {
        this.initReporter.setReportForPublication(initReport);
      }
    }

    if (!this.ctxAndAddresses.compareAndSet(prevContextAndAddresses, newContextAndAddresses)) {
      newPwafCtx.delReference();
      throw new AppSecModuleActivationException("Concurrent update of WAF configuration");
    }

    if (prevContextAndAddresses != null) {
      prevContextAndAddresses.ctx.delReference();
    }

    reconf.reloadSubscriptions();
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

    return "powerwaf(libddwaf: "
        + Powerwaf.LIB_VERSION
        + ") loaded "
        + ctxAndAddresses.rulesInfoMap.size()
        + " rules";
  }

  @Override
  public Collection<EventSubscription> getEventSubscriptions() {
    return singletonList(new PowerWAFEventsCallback());
  }

  private static class PowerWAFEventsCallback extends EventSubscription {
    public PowerWAFEventsCallback() {
      super(EventType.REQUEST_END, Priority.DEFAULT);
    }

    @Override
    public void onEvent(AppSecRequestContext reqCtx, EventType eventType) {
      if (eventType == EventType.REQUEST_END) {
        reqCtx.closeAdditive();
      }
    }
  }

  @Override
  public Collection<DataSubscription> getDataSubscriptions() {
    if (this.ctxAndAddresses.get() == null) {
      log.warn("No subscriptions provided because module is not configured");
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
      try {
        StandardizedLogging.executingWAF(log);
        long start = 0L;
        if (log.isDebugEnabled()) {
          start = System.currentTimeMillis();
        }

        resultWithData = doRunPowerwaf(reqCtx, newData, ctxAndAddr, isTransient);

        if (log.isDebugEnabled()) {
          long elapsed = System.currentTimeMillis() - start;
          StandardizedLogging.finishedExecutionWAF(log, elapsed);
        }

      } catch (AbstractPowerwafException e) {
        log.error("Error calling WAF", e);
        return;
      }

      StandardizedLogging.inAppWafReturn(log, resultWithData);

      if (resultWithData.result != Powerwaf.Result.OK) {
        if (log.isDebugEnabled()) {
          log.warn("WAF signalled result {}: {}", resultWithData.result, resultWithData.data);
        }

        if (resultWithData.actions.length > 0) {
          for (String action : resultWithData.actions) {
            ActionInfo actionInfo = ctxAndAddr.actionInfoMap.get(action);
            if (actionInfo == null) {
              log.warn(
                  "WAF indicated action {}, but such action id is unknown (not one from {})",
                  action,
                  ctxAndAddr.actionInfoMap.keySet());
            } else if ("block_request".equals(actionInfo.type)) {
              Flow.Action.RequestBlockingAction rba = createRequestBlockingAction(actionInfo);
              flow.setAction(rba);
              break;
            } else {
              log.info("Ignoring action with type {}", actionInfo.type);
            }
          }
        }
        Collection<AppSecEvent100> events = buildEvents(resultWithData, ctxAndAddr.rulesInfoMap);
        reqCtx.reportEvents(events, null);
      }
    }

    private Flow.Action.RequestBlockingAction createRequestBlockingAction(ActionInfo actionInfo) {
      try {
        int statusCode =
            ((Number) actionInfo.parameters.getOrDefault("status_code", 403)).intValue();
        String contentType = (String) actionInfo.parameters.getOrDefault("type", "auto");
        Flow.Action.BlockingContentType blockingContentType = Flow.Action.BlockingContentType.AUTO;
        try {
          blockingContentType =
              Flow.Action.BlockingContentType.valueOf(contentType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException iae) {
          log.warn("Unknown content type: {}; using auto", contentType);
        }
        return new Flow.Action.RequestBlockingAction(statusCode, blockingContentType);
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

  private Collection<AppSecEvent100> buildEvents(
      Powerwaf.ResultWithData actionWithData, Map<String, RuleInfo> rulesInfoMap) {
    Collection<PowerWAFResultData> listResults;
    try {
      listResults = RES_JSON_ADAPTER.fromJson(actionWithData.data);
    } catch (IOException e) {
      throw new UndeclaredThrowableException(e);
    }

    if (listResults != null && !listResults.isEmpty()) {
      return listResults.stream()
          .map(wafResult -> buildEvent(wafResult, rulesInfoMap))
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }
    return emptyList();
  }

  private AppSecEvent100 buildEvent(
      PowerWAFResultData wafResult, Map<String, RuleInfo> rulesInfoMap) {

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

    RuleInfo ruleInfo = rulesInfoMap.get(wafResult.rule.id);

    return new AppSecEvent100.AppSecEvent100Builder()
        .withRule(
            new Rule.RuleBuilder()
                .withId(wafResult.rule.id)
                .withName(ruleInfo.name)
                .withTags(
                    new Tags.TagsBuilder()
                        .withType(ruleInfo.tags.get("type"))
                        .withCategory(ruleInfo.tags.get("category"))
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

  class FilledInRuleTogglingMap extends AbstractMap<String, Boolean> {
    final Map<String, Boolean> explicitCfg;
    final Set<String> rulesNames;
    private int size = -1;

    FilledInRuleTogglingMap(Map<String, Boolean> explicitCfg, Set<String> allRuleNames) {
      this.explicitCfg = explicitCfg;
      this.rulesNames = allRuleNames;
    }

    @Override
    public Set<Entry<String, Boolean>> entrySet() {
      return this.rulesNames.stream()
          .map(
              ruleName ->
                  new RuleEnabledEntry(
                      ruleName,
                      this.explicitCfg.containsKey(ruleName)
                          ? this.explicitCfg.get(ruleName)
                          : Boolean.TRUE))
          .collect(Collectors.toSet());
    }
  }

  static class RuleEnabledEntry implements Map.Entry<String, Boolean> {
    final String ruleName;
    final boolean enabled;

    RuleEnabledEntry(String ruleName, boolean enabled) {
      this.ruleName = ruleName;
      this.enabled = enabled;
    }

    @Override
    public String getKey() {
      return this.ruleName;
    }

    @Override
    public Boolean getValue() {
      return this.enabled;
    }

    @Override
    public Boolean setValue(Boolean value) {
      throw new UnsupportedOperationException();
    }
  }
}
