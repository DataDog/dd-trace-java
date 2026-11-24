package com.datadog.appsec.event.data;

/** Exposes package-private {@link Address} members to tests in other packages. */
public final class AddressTestBridge {

  private AddressTestBridge() {}

  public static <T> Address<T> newAddress(String key) {
    return new Address<>(key);
  }
}
