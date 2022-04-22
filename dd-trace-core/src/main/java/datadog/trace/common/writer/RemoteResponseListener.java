package datadog.trace.common.writer;

import java.util.Map;

public interface RemoteResponseListener {
  /** Invoked after the api receives a response from the remote service. */
  void onResponse(String endpoint, Map<String, Map<String, Number>> responseJson);
}
