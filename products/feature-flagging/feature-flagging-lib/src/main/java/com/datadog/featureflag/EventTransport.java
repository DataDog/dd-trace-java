package com.datadog.featureflag;

import java.io.IOException;

/** Sends one serialized event batch. Routing and retry policy belong to the assembly. */
@FunctionalInterface
public interface EventTransport {
  void post(String route, byte[] json) throws IOException;
}
