package com.datadog.openfeature.internal.delivery;

import com.datadog.openfeature.internal.config.SdkVersion;
import java.net.http.HttpRequest;

/** Common Datadog request headers. */
final class Headers {
  private static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
  private static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "unknown");
  private static final String JAVA_VM_VENDOR = System.getProperty("java.vm.vendor", "unknown");

  private Headers() {}

  /**
   * Adds the language and SDK metadata headers.
   *
   * @param request the request to add the headers to.
   */
  // TODO Add the Datadog-Container-ID and Datadog-Entity-ID headers once the SDK detects them.
  static void addMetadata(final HttpRequest.Builder request) {
    request
        .header("Datadog-Meta-Lang", "java")
        .header("Datadog-Meta-Lang-Version", JAVA_VERSION)
        .header("Datadog-Meta-Lang-Interpreter", JAVA_VM_NAME)
        .header("Datadog-Meta-Lang-Interpreter-Vendor", JAVA_VM_VENDOR)
        .header("Datadog-Meta-Tracer-Version", SdkVersion.VERSION);
  }
}
