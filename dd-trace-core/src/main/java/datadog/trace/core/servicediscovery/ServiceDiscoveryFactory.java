package datadog.trace.core.servicediscovery;

import java.util.function.Supplier;

@FunctionalInterface
public interface ServiceDiscoveryFactory extends Supplier<ServiceDiscovery> {}
