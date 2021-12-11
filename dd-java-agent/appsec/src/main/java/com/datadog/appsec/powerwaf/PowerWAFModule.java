package com.datadog.appsec.powerwaf;

import static java.util.Collections.singletonList;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.AppSecConfigService;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.datadog.appsec.report.raw.events.Parameter100;
import com.datadog.appsec.report.raw.events.Rule100;
import com.datadog.appsec.report.raw.events.RuleMatch100;
import com.datadog.appsec.util.StandardizedLogging;
import com.google.auto.service.AutoService;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.gateway.Flow;
import io.sqreen.powerwaf.Additive;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(AppSecModule.class)
public final class PowerWAFModule implements AppSecModule {
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
  private static final Set<Address<?>> ADDRESSES_OF_INTEREST;
  private static final Set<EventType> EVENTS_OF_INTEREST;

  private static final JsonAdapter<List<PowerWAFResultData>> RES_JSON_ADAPTER;

  private static final Map<String, RuleInfo> rulesInfoMap = new ConcurrentHashMap<>();

  private static final class RuleInfo {
    final String name;
    final String type;
    final Map<String, String> tags;

    RuleInfo(AppSecConfig.Rule rule) {
      this.name = rule.getName();
      this.type = rule.getTags().getOrDefault("type", "waf");
      this.tags = rule.getTags();
    }
  }

  static {
    try {
      PROXY_CLASS_CONSTRUCTOR = PROXY_CLASS.getConstructor(InvocationHandler.class);
    } catch (NoSuchMethodException e) {
      throw new UndeclaredThrowableException(e);
    }

    ADDRESSES_OF_INTEREST = new HashSet<>();
    ADDRESSES_OF_INTEREST.add(KnownAddresses.REQUEST_URI_RAW);
    ADDRESSES_OF_INTEREST.add(KnownAddresses.REQUEST_QUERY);
    ADDRESSES_OF_INTEREST.add(KnownAddresses.HEADERS_NO_COOKIES);
    ADDRESSES_OF_INTEREST.add(KnownAddresses.REQUEST_COOKIES);
    ADDRESSES_OF_INTEREST.add(KnownAddresses.REQUEST_PATH_PARAMS);
    ADDRESSES_OF_INTEREST.add(KnownAddresses.REQUEST_BODY_RAW);

    EVENTS_OF_INTEREST = new HashSet<>();
    EVENTS_OF_INTEREST.add(EventType.REQUEST_START);
    EVENTS_OF_INTEREST.add(EventType.REQUEST_END);

    Moshi moshi = new Moshi.Builder().build();
    RES_JSON_ADAPTER =
        moshi.adapter(Types.newParameterizedType(List.class, PowerWAFResultData.class));
  }

  private final AtomicReference<PowerwafContext> ctx = new AtomicReference<>();

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
  }

  private void applyConfig(AppSecConfig config) throws AppSecModuleActivationException {
    log.info("Configuring WAF");

    PowerwafContext prevContext = this.ctx.get();
    PowerwafContext newContext;

    if (!LibSqreenInitialization.ONLINE) {
      throw new AppSecModuleActivationException(
          "In-app WAF initialization failed. See previous log entries");
    } else {
      try {
        String uniqueId = UUID.randomUUID().toString();
        newContext = Powerwaf.createContext(uniqueId, config.getRawConfig());

        rulesInfoMap.clear();
        config.getRules().forEach(e -> rulesInfoMap.put(e.getId(), new RuleInfo(e)));

      } catch (RuntimeException | AbstractPowerwafException e) {
        throw new AppSecModuleActivationException("Error creating WAF rules", e);
      }
    }

    if (!this.ctx.compareAndSet(prevContext, newContext)) {
      throw new AppSecModuleActivationException("Concurrent update of WAF configuration");
    }

    if (prevContext != null) {
      prevContext.delReference();
    }
  }

  @Override
  public String getName() {
    return "powerwaf";
  }

  @Override
  public Collection<EventSubscription> getEventSubscriptions() {
    return singletonList(new PowerWAFEventsCallback());
  }

  private static final class PowerWAFEventsCallback extends EventSubscription {
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
    return singletonList(new PowerWAFDataCallback());
  }

  private final class PowerWAFDataCallback extends DataSubscription {
    public PowerWAFDataCallback() {
      super(ADDRESSES_OF_INTEREST, Priority.DEFAULT);
    }

    @Override
    public void onDataAvailable(
        ChangeableFlow flow, AppSecRequestContext reqCtx, DataBundle newData) {
      Powerwaf.ActionWithData actionWithData;
      PowerwafContext powerwafContext = ctx.get();
      if (powerwafContext == null) {
        log.debug("Skipped; the WAF is not configured");
        return;
      }
      try {
        StandardizedLogging.executingWAF(log);
        long start = 0L;
        if (log.isDebugEnabled()) {
          start = System.currentTimeMillis();
        }

        Additive additive = reqCtx.getAdditive();
        if (additive == null) {
          additive = powerwafContext.openAdditive();
          reqCtx.setAdditive(additive);
        }
        actionWithData = additive.run(new DataBundleMapWrapper(newData), LIMITS);

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
        log.warn("WAF signalled action {}: {}", actionWithData.action, actionWithData.data);
        flow.setAction(new Flow.Action.Throw(new RuntimeException("WAF wants to block")));

        reqCtx.setBlocked(actionWithData.action == Powerwaf.Action.BLOCK);
        buildEvent(actionWithData).ifPresent(reqCtx::reportEvent);
      }
    }
  }

  private Optional<AppSecEvent100> buildEvent(Powerwaf.ActionWithData actionWithData) {
    List<PowerWAFResultData> listResults;
    try {
      listResults = RES_JSON_ADAPTER.fromJson(actionWithData.data);
    } catch (IOException e) {
      throw new UndeclaredThrowableException(e);
    }

    if (listResults == null || listResults.isEmpty()) {
      return Optional.empty();
    }

    // we only take the first match
    PowerWAFResultData powerWAFResultData = listResults.get(0);
    if (powerWAFResultData.rule_matches == null || powerWAFResultData.rule_matches.isEmpty()) {
      return Optional.empty();
    }
    PowerWAFResultData.RuleMatch ruleMatch = powerWAFResultData.rule_matches.get(0);
    PowerWAFResultData.Parameter parameter = ruleMatch.parameters.get(0);

    RuleInfo ruleInfo = rulesInfoMap.get(powerWAFResultData.rule.id);

    AppSecEvent100 event =
        new AppSecEvent100.AppSecEvent100Builder()
            .withEventType(ruleInfo.type)
            .withRule(
                new Rule100.Rule100Builder()
                    .withId(powerWAFResultData.rule.id)
                    .withName(ruleInfo.name)
                    .withTags(ruleInfo.tags)
                    .build())
            .withRuleMatch(
                new RuleMatch100.RuleMatch100Builder()
                    .withOperator(ruleMatch.operator)
                    .withOperatorValue(ruleMatch.operator_value)
                    .withHighlight(parameter.highlight)
                    .withParameters(
                        singletonList(
                            new Parameter100.Parameter100Builder()
                                .withAddress(parameter.address)
                                .withKeyPath(parameter.key_path)
                                .withValue(parameter.value)
                                .build()))
                    .build())
            .build();

    return Optional.of(event);
  }

  private static final class DataBundleMapWrapper implements Map<String, Object> {
    private final DataBundle dataBundle;

    private DataBundleMapWrapper(DataBundle dataBundle) {
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

    private final class SetIteratorInvocationHandler implements InvocationHandler {
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
                ADDRESSES_OF_INTEREST.contains(next)
                    ? dataBundle.get(next)
                    : Collections.emptyMap();
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
