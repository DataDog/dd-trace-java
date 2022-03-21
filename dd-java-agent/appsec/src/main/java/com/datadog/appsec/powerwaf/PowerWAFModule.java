package com.datadog.appsec.powerwaf;

import static java.util.Collections.*;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.AppSecConfigService;
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

  private static class CtxAndAddresses {
    final Collection<Address<?>> addressesOfInterest;
    final PowerwafContext ctx;
    final Map<String, RuleInfo> rulesInfoMap;

    private CtxAndAddresses(
        Collection<Address<?>> addressesOfInterest,
        PowerwafContext ctx,
        Map<String, RuleInfo> rulesInfoMap) {
      this.addressesOfInterest = addressesOfInterest;
      this.ctx = ctx;
      this.rulesInfoMap = rulesInfoMap;
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
  }

  private final boolean wafMetricsEnabled =
      Config.get().isAppSecWafMetrics(); // could be static if not for tests
  private final AtomicReference<CtxAndAddresses> ctxAndAddresses = new AtomicReference<>();
  private final PowerWAFInitializationResultReporter initReporter =
      new PowerWAFInitializationResultReporter();

  @Override
  public void config(AppSecConfigService appSecConfigService)
      throws AppSecModuleActivationException {
    Optional<AppSecConfig> initialConfig =
        appSecConfigService.addSubConfigListener("waf", this::applyConfig);
    if (!initialConfig.isPresent()) {
      throw new AppSecModuleActivationException("No initial config for WAF");
    }
    try {
      applyConfig(initialConfig.get());
    } catch (ClassCastException e) {
      throw new AppSecModuleActivationException("Config expected to be AppSecConfig", e);
    }

    appSecConfigService.addTraceSegmentPostProcessor(initReporter);
    if (wafMetricsEnabled) {
      appSecConfigService.addTraceSegmentPostProcessor(new PowerWAFStatsReporter());
    }
  }

  private void applyConfig(AppSecConfig config) throws AppSecModuleActivationException {
    if (log.isDebugEnabled()) {
      log.info("Configuring WAF");
    }

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
      newPwafCtx = Powerwaf.createContext(uniqueId, config.getRawConfig());
      initReport = newPwafCtx.getRuleSetInfo();
      Collection<Address<?>> addresses = getUsedAddresses(newPwafCtx);

      Map<String, RuleInfo> rulesInfoMap = new HashMap<>();
      config.getRules().forEach(e -> rulesInfoMap.put(e.getId(), new RuleInfo(e)));

      newContextAndAddresses = new CtxAndAddresses(addresses, newPwafCtx, rulesInfoMap);
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
        Additive additive = reqCtx.getAdditive();
        if (additive != null) {
          additive.close();
        }

        reqCtx.setAdditive(null);
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
        ChangeableFlow flow, AppSecRequestContext reqCtx, DataBundle newData) {
      Powerwaf.ActionWithData actionWithData;
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

        actionWithData = doRunPowerwaf(reqCtx, newData, ctxAndAddr);

        if (log.isDebugEnabled()) {
          long elapsed = System.currentTimeMillis() - start;
          StandardizedLogging.finishedExecutionWAF(log, elapsed);
        }

      } catch (AbstractPowerwafException e) {
        log.error("Error calling WAF", e);
        return;
      }

      StandardizedLogging.inAppWafReturn(log, actionWithData);

      if (actionWithData.action != Powerwaf.Action.OK) {
        if (log.isDebugEnabled()) {
          log.warn("WAF signalled action {}: {}", actionWithData.action, actionWithData.data);
        }
        flow.setAction(new Flow.Action.Throw(new RuntimeException("WAF wants to block")));

        if (actionWithData.action == Powerwaf.Action.BLOCK) {
          reqCtx.setBlocked(true);
        }
        Collection<AppSecEvent100> events = buildEvents(actionWithData, ctxAndAddr.rulesInfoMap);
        reqCtx.reportEvents(events, null);
      }
    }

    private Powerwaf.ActionWithData doRunPowerwaf(
        AppSecRequestContext reqCtx, DataBundle newData, CtxAndAddresses ctxAndAddr)
        throws AbstractPowerwafException {
      Additive additive;
      PowerwafMetrics metrics = null;

      synchronized (reqCtx) {
        additive = reqCtx.getAdditive();
        if (additive == null) {
          additive = ctxAndAddr.ctx.openAdditive();
          reqCtx.setAdditive(additive);
          if (wafMetricsEnabled) {
            metrics = ctxAndAddr.ctx.createMetrics();
            reqCtx.setWafMetrics(metrics);
          }
        } else {
          metrics = reqCtx.getWafMetrics();
        }
      }

      boolean isTransient =
          newData.getAllAddresses().stream().anyMatch(addr -> !reqCtx.hasAddress(addr));
      if (isTransient) {
        DataBundle bundle = DataBundle.unionOf(newData, reqCtx);
        return runPowerwafTransient(metrics, bundle, ctxAndAddr);
      } else {
        return runPowerwafAdditive(additive, metrics, newData, ctxAndAddr);
      }
    }

    private Powerwaf.ActionWithData runPowerwafAdditive(
        Additive additive, PowerwafMetrics metrics, DataBundle newData, CtxAndAddresses ctxAndAddr)
        throws AbstractPowerwafException {
      return additive.run(
          new DataBundleMapWrapper(ctxAndAddr.addressesOfInterest, newData), LIMITS, metrics);
    }
  }

  private Powerwaf.ActionWithData runPowerwafTransient(
      PowerwafMetrics metrics, DataBundle bundle, CtxAndAddresses ctxAndAddr)
      throws AbstractPowerwafException {
    return ctxAndAddr.ctx.runRules(
        new DataBundleMapWrapper(ctxAndAddr.addressesOfInterest, bundle), LIMITS, metrics);
  }

  private Collection<AppSecEvent100> buildEvents(
      Powerwaf.ActionWithData actionWithData, Map<String, RuleInfo> rulesInfoMap) {
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
}
