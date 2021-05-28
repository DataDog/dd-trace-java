package com.datadog.appsec.event.data;

import java.util.concurrent.atomic.AtomicInteger;

/** @param <T> the type of data associated with the address */
public final class Address<T> {
  private static final AtomicInteger NEXT_SERIAL = new AtomicInteger();

  private final String key;
  private final int serial;

  // instances are created in KnownAddresses
  Address(String key) {
    this.key = key;
    this.serial = NEXT_SERIAL.getAndIncrement();
  }

  public static int instanceCount() {
    return NEXT_SERIAL.get();
  }

  public String getKey() {
    return key;
  }

  public int getSerial() {
    return serial;
  }

  // do not replace equals/hashcode
  // equality is identity

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("Address{");
    sb.append("key='").append(key).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
