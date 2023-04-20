package datadog.trace.instrumentation.kafka_clients;

import org.apache.kafka.common.header.Headers;

public class HeadersHelper {
  private static final String DsmDisabledHeader = "dsm_disabled";

  public static void AddDsmDisabledHeader(Headers headers) {
    headers.add(DsmDisabledHeader, null);
  }

  public static void RemoveDsmDisabledHeader(Headers headers) {
    headers.remove(DsmDisabledHeader);
  }

  public static boolean HasDsmDisabledHeader(Headers headers) {
    return headers.lastHeader(DsmDisabledHeader) != null;
  }
}
