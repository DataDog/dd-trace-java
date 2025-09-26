package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.MalformedURLException;
import java.net.URL;

public final class CapturingPathResolver implements PathLocator {
  final int simulateNotFoundCount;
  int numRequests;

  String requestedComponent;
  String requestedPath;

  public CapturingPathResolver() {
    this(0);
  }

  public CapturingPathResolver(int simulateNotFoundCount) {
    this.numRequests = 0;
    this.simulateNotFoundCount = simulateNotFoundCount;
  }

  @Override
  public URL locate(String component, String path) {
    if (this.numRequests++ < this.simulateNotFoundCount) return null;

    this.requestedComponent = component;
    this.requestedPath = path;

    try {
      return new URL("http://localhost");
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  void assertRequested(String expectedComponent, String expectedPath) {
    assertEquals(expectedComponent, this.requestedComponent);
    assertEquals(expectedPath, this.requestedPath);
  }
}
