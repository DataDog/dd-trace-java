package datadog.trace.security;

import java.util.Set;

public interface DataSubscription extends Comparable<DataSubscription> {
    boolean matches(Set<String> newAddressKeys, DataSource ds);

    Set<String> getAllAddressKeys();

    DataAvailableListener getListener();
}
