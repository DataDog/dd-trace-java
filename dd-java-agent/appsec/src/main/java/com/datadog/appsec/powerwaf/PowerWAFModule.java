package com.datadog.appsec.powerwaf;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.AppSecRequestContext;
import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.OrderedCallback;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.google.auto.service.AutoService;
import datadog.trace.api.gateway.Flow;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(AppSecModule.class)
public class PowerWAFModule implements AppSecModule {
  private static final Logger LOG = LoggerFactory.getLogger(AppSecSystem.class);

  private static final int MAX_DEPTH = 10;
  private static final int MAX_ELEMENTS = 150;
  private static final int MAX_STRING_SIZE = 4096;
  private static final String RULE_NAME = "waf";
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
      Proxy.getProxyClass(PowerWAFModule.class.getClassLoader(), new Class<?>[] {Set.class});
  private static final Constructor<?> PROXY_CLASS_CONSTRUCTOR;
  private static final Set<Address<?>> ADDRESSES_OF_INTEREST;

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
  }

  private final PowerwafContext ctx;

  public PowerWAFModule() {
    PowerwafContext ctx = null;

    if (!LibSqreenInitialization.ONLINE) {
      LOG.warn("In-app WAF initialization failed");
    } else {
      try {
        String wafDef = loadWAFJson();
        String uniqueId = UUID.randomUUID().toString();
        ctx = Powerwaf.createContext(uniqueId, Collections.singletonMap(RULE_NAME, wafDef));
      } catch (IOException e) {
        LOG.error("Error reading WAF atom", e);
      } catch (AbstractPowerwafException e) {
        LOG.error("Error creating WAF atom", e);
      }
    }

    this.ctx = ctx;
  }

  @Override
  public String getName() {
    return "powerwaf";
  }

  @Override
  public Collection<EventSubscription> getEventSubscriptions() {
    return Collections.emptyList();
  }

  @Override
  public Collection<DataSubscription> getDataSubscriptions() {
    if (ctx == null) {
      return Collections.emptyList();
    }

    return Collections.singletonList(new PowerWAFDataCallback());
  }

  private class PowerWAFDataCallback extends DataSubscription {
    public PowerWAFDataCallback() {
      super(ADDRESSES_OF_INTEREST, OrderedCallback.DEFAULT_PRIORITY);
    }

    @Override
    public void onDataAvailable(
        ChangeableFlow flow, AppSecRequestContext reqCtx, DataBundle newData) {
      Powerwaf.ActionWithData actionWithData;
      try {
        actionWithData = ctx.runRule(RULE_NAME, new DataBundleMapWrapper(newData), LIMITS);
      } catch (AbstractPowerwafException e) {
        LOG.error("Error calling WAF", e);
        return;
      }

      if (actionWithData.action != Powerwaf.Action.OK) {
        LOG.warn("WAF signalled action {}: {}", actionWithData.action, actionWithData.data);
      }

      flow.setBlockingAction(new Flow.Action.Throw(new RuntimeException("WAF wants to block")));
    }
  }

  private final class DataBundleMapWrapper implements Map<String, Object> {
    private final DataBundle dataBundle;

    private DataBundleMapWrapper(DataBundle dataBundle) {
      this.dataBundle = dataBundle;
    }

    // powerwaf only calls entrySet().iterator()
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
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("iterator")) {
          throw new UnsupportedOperationException();
        }

        final Iterator<Address<?>> addrIterator = dataBundle.getAllAddresses().iterator();
        final MutableEntry entry = new MutableEntry();

        return new Iterator<Entry<String, Object>>() {
          private Address<?> next = computeNextAddress();

          private Address<?> computeNextAddress() {
            while (addrIterator.hasNext()) {
              Address<?> next = addrIterator.next();
              if (ADDRESSES_OF_INTEREST.contains(next)) {
                return next;
              }
            }
            return null;
          }

          @Override
          public boolean hasNext() {
            return next != null;
          }

          @Override
          public Entry<String, Object> next() {
            // the usage pattern in powerwaf allows object recycling here
            entry.key = next.getKey();
            entry.value = dataBundle.get(next);
            next = computeNextAddress();
            return entry;
          }
        };
      }
    }

    /* unimplemented map methods */
    @Override
    public int size() {
      throw new UnsupportedOperationException();
    }

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
    public void putAll(Map<? extends String, ?> m) {
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

  private String loadWAFJson() throws IOException {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("waf.json")) {
      InputStreamReader reader = new InputStreamReader(is, Charset.forName("UTF-8"));
      StringBuilder sbuf = new StringBuilder();
      char[] buf = new char[8192];
      int read;
      do {
        read = reader.read(buf);
        sbuf.append(buf, 0, read);
      } while (read > 0);
      String str = sbuf.toString();
      return str;
    }
  }
}
