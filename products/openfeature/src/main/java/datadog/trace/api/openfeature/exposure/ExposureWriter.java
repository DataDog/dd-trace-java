package datadog.trace.api.openfeature.exposure;

/**
 * Defines an exposure writer responsible for sending exposure events to the EVP proxy.
 * Implementations should use a background thread to perform these operations asynchronously.
 */
public interface ExposureWriter extends AutoCloseable, ExposureListener {

  void init();

  void close();
}
