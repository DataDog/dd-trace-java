package com.datadog.appsec;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AppSecInterface {

  private final Map<Address<?>, Set<Callback>> subscriptions = new ConcurrentHashMap<>();

  @SuppressWarnings("rawtypes")
  public void subscribeCallback(Callback cb) {
    Set<Address> addresses = cb.getRequiredAddresses();
    if (addresses != null) {
      addresses.forEach(addr -> {
        Set<Callback> callbacks = subscriptions.computeIfAbsent(addr, k -> new LinkedHashSet<>());
        callbacks.add(cb);
      });
    }
  }

  public void unsubscribeCallback(Callback cb) {
    cb.getRequiredAddresses().forEach(addr -> {
      Set<Callback> callbacks = subscriptions.get(addr);
      if (callbacks != null) {
        callbacks.remove(cb);
        // If no more callbacks left - remove address
        if (callbacks.isEmpty()) {
          subscriptions.remove(addr);
        }
      }
    });
  }
}
