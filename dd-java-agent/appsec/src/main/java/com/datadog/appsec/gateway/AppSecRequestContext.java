package com.datadog.appsec.gateway;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.StringKVPair;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: different methods to be called by different parts perhaps splitting it would make sense
// or at least create separate interfaces
public class AppSecRequestContext
    implements DataBundle, datadog.trace.api.gateway.RequestContext, Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(AppSecSystem.class);

  private final ConcurrentHashMap<Address<?>, Object> persistentData = new ConcurrentHashMap<>();

  private String savedRawURI;
  private CaseInsensitiveMap<List<String>> collectedHeaders = new CaseInsensitiveMap<>();
  private List<StringKVPair> collectedCookies = new ArrayList<StringKVPair>(4);

  // to be called by the Event Dispatcher
  public void addAll(DataBundle newData) {
    for (Map.Entry<Address<?>, Object> entry : newData) {
      Object prev = persistentData.putIfAbsent(entry.getKey(), entry.getValue());
      if (prev != null) {
        LOG.warn("Illegal attempt to replace context value for {}", entry.getKey());
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
  public <T> T get(Address<T> addr) {
    return (T) persistentData.get(addr);
  }

  @Override
  public Iterator<Map.Entry<Address<?>, Object>> iterator() {
    return persistentData.entrySet().iterator();
  }

  /* Interface for use of GatewayBridge */

  String getSavedRawURI() {
    if (collectedHeaders == null) {
      throw new IllegalStateException("Headers finished, collection not possible anymore");
    }
    return savedRawURI;
  }

  void setRawURI(String savedRawURI) {
    if (collectedHeaders == null) {
      LOG.warn("Saw raw URI after headers have finished");
      return;
    }
    this.savedRawURI = savedRawURI;
  }

  void addHeader(String name, String value) {
    if (collectedHeaders == null) {
      LOG.warn("Header provided after headers ended");
      return;
    }
    List<String> strings = collectedHeaders.get(name);
    if (strings == null) {
      strings = new ArrayList<>(1);
      collectedHeaders.put(name, strings);
    }
    strings.add(value);
  }

  void addCookie(StringKVPair cookie) {
    if (collectedCookies == null) {
      LOG.warn("Cookie provided after headers ended");
      return;
    }
    collectedCookies.add(cookie);
  }

  void finishHeaders() {
    this.savedRawURI = null;
    this.collectedHeaders = null;
    this.collectedCookies = null;
  }

  CaseInsensitiveMap<List<String>> getCollectedHeaders() {
    if (collectedHeaders == null) {
      throw new IllegalStateException("Headers finished, collection not possible anymore");
    }
    return collectedHeaders;
  }

  List<StringKVPair> getCollectedCookies() {
    if (collectedCookies == null) {
      throw new IllegalStateException("Headers finished, collection not possible anymore");
    }
    return collectedCookies;
  }

  @Override
  public void close() {
    // currently no-op
  }
}
