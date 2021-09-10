package com.datadog.appsec.gateway;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.StringKVPair;
import com.datadog.appsec.report.ReportService;
import com.datadog.appsec.report.raw.events.attack.Attack010;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.StoredBodySupplier;
import java.io.Closeable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: different methods to be called by different parts perhaps splitting it would make sense
// or at least create separate interfaces
public class AppSecRequestContext implements DataBundle, RequestContext, ReportService, Closeable {
  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);

  private final ConcurrentHashMap<Address<?>, Object> persistentData = new ConcurrentHashMap<>();
  private Collection<Attack010> collectedAttacks; // guarded by this

  // assume these will always be written and read by the same thread
  private String scheme;
  private String method;
  private String savedRawURI;
  private final CaseInsensitiveMap<List<String>> collectedHeaders = new CaseInsensitiveMap<>();
  private List<StringKVPair> collectedCookies = new ArrayList<>(4);
  private boolean finishedHeaders;
  private String peerAddress;
  private int peerPort;

  private volatile StoredBodySupplier storedRequestBodySupplier;

  // to be called by the Event Dispatcher
  public void addAll(DataBundle newData) {
    for (Map.Entry<Address<?>, Object> entry : newData) {
      Object prev = persistentData.putIfAbsent(entry.getKey(), entry.getValue());
      if (prev != null) {
        log.warn("Illegal attempt to replace context value for {}", entry.getKey());
      }
      if (log.isDebugEnabled()) {
        StandardizedLogging.addressPushed(log, entry.getKey());
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

  String getScheme() {
    return scheme;
  }

  void setScheme(String scheme) {
    this.scheme = scheme;
  }

  String getMethod() {
    return method;
  }

  void setMethod(String method) {
    this.method = method;
  }

  String getSavedRawURI() {
    return savedRawURI;
  }

  void setRawURI(String savedRawURI) {
    if (this.savedRawURI != null) {
      throw new IllegalStateException("Raw URI set already");
    }
    this.savedRawURI = savedRawURI;
  }

  void addHeader(String name, String value) {
    if (finishedHeaders) {
      throw new IllegalStateException("Headers were said to be finished before");
    }
    List<String> strings = collectedHeaders.get(name);
    if (strings == null) {
      strings = new ArrayList<>(1);
      collectedHeaders.put(name, strings);
    }
    strings.add(value);
  }

  void addCookie(StringKVPair cookie) {
    if (finishedHeaders) {
      throw new IllegalStateException("Headers were said to be finished before");
    }
    collectedCookies.add(cookie);
  }

  void finishHeaders() {
    this.finishedHeaders = true;
  }

  boolean isFinishedHeaders() {
    return finishedHeaders;
  }

  CaseInsensitiveMap<List<String>> getCollectedHeaders() {
    return collectedHeaders;
  }

  List<StringKVPair> getCollectedCookies() {
    return collectedCookies;
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

  void setStoredRequestBodySupplier(StoredBodySupplier storedRequestBodySupplier) {
    this.storedRequestBodySupplier = storedRequestBodySupplier;
  }

  @Override
  public void close() {
    // currently no-op
  }

  /* end interface for GatewayBridge */

  /* Should be accessible from the modules */

  /** @return the portion of the body read so far, if any */
  public CharSequence getStoredRequestBody() {
    StoredBodySupplier storedRequestBodySupplier = this.storedRequestBodySupplier;
    if (storedRequestBodySupplier == null) {
      return null;
    }
    return storedRequestBodySupplier.get();
  }

  @Override
  public void reportAttack(Attack010 attack) {
    StandardizedLogging.attackDetected(log, attack);

    if (attack.getDetectedAt() == null) {
      attack.setDetectedAt(Instant.now());
    }
    synchronized (this) {
      if (this.collectedAttacks == null) {
        this.collectedAttacks = new ArrayList<>();
      }
      try {
        this.collectedAttacks.add(attack);
      } catch (UnsupportedOperationException e) {
        throw new IllegalStateException("Attacks cannot be added anymore");
      }
    }
  }

  Collection<Attack010> transferCollectedAttacks() {
    Collection<Attack010> collectedAttacks;
    synchronized (this) {
      collectedAttacks = this.collectedAttacks;
      this.collectedAttacks = Collections.emptyList();
    }
    if (collectedAttacks != null) {
      return collectedAttacks;
    } else {
      return Collections.emptyList();
    }
  }
}
