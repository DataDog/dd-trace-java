package datadog.trace.api.appsec;

import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import java.util.Map;

public interface LoginEventCallback {

  Flow<Void> apply(
      RequestContext context,
      UserIdCollectionMode mode,
      String eventName,
      Boolean exists,
      String user,
      Map<String, String> metadata);
}
