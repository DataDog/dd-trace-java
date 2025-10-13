package datadog.trace.core.servicediscovery;

@FunctionalInterface
public interface ServiceDiscoveryFactory {
  ServiceDiscovery createServiceDiscovery();
}
