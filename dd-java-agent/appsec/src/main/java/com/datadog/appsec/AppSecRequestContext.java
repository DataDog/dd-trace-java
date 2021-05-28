package com.datadog.appsec;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.StringKVPair;
import java.io.Closeable;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecRequestContext
    implements DataBundle, datadog.trace.api.gateway.RequestContext, Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(AppSecSystem.class);

  private final ConcurrentHashMap<Address<?>, Object> persistentData = new ConcurrentHashMap<>();

  private String savedRawURI;
  private CaseInsensitiveMap<List<String>> collectedHeaders;
  private List<HttpCookie> collectedCookies;

  public void addAll(DataBundle newData) {
    for (Map.Entry<Address<?>, Object> entry : newData) {
      Object prev = persistentData.putIfAbsent(entry.getKey(), entry.getValue());
      if (prev != null) {
        LOG.warn("Illegal attempt to replace context value for {}", entry.getKey());
      }
    }
  }

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

  public String getSavedRawURI() {
    return savedRawURI;
  }

  public void setRawURI(String savedRawURI) {
    if (collectedHeaders == null) {
      LOG.warn("Saw raw URI after headers have finished");
      return;
    }
    this.savedRawURI = savedRawURI;
  }

  public void addHeader(String name, String value) {
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

  public void addCookie(HttpCookie cookie) {
    if (collectedCookies == null) {
      LOG.warn("Cookie provided after headers ended");
      return;
    }
    collectedCookies.add(cookie);
  }

  public void finishHeaders() {
    this.savedRawURI = null;
    this.collectedHeaders = null;
    this.collectedCookies = null;
  }

  public CaseInsensitiveMap<List<String>> getCollectedHeaders() {
    return collectedHeaders;
  }

  public List<StringKVPair> getCollectedCookies() {
    ArrayList<StringKVPair> cookies = new ArrayList<>(collectedCookies.size());
    for (HttpCookie collectedCookie : collectedCookies) {
      cookies.add(new StringKVPair(collectedCookie.getName(), collectedCookie.getValue()));
    }
    return cookies;
  }

  @Override
  public void close() {
    // currently no-op
  }
}
