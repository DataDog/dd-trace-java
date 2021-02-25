package datadog.trace.api.sqreen;

import java.util.Set;

public abstract class Engine {
  public static Engine INSTANCE;

  public abstract void deliverNotifications(Set<String> newAddressKeys);
}
