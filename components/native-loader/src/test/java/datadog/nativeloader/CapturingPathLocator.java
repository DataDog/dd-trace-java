package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Deque;
import java.util.LinkedList;

public final class CapturingPathLocator implements PathLocator {
  final int simulateNotFoundCount;
  int numRequests;

  final Deque<LocateRequest> locateRequests = new LinkedList<>();

  public CapturingPathLocator() {
    this(0);
  }

  public CapturingPathLocator(int simulateNotFoundCount) {
    this.numRequests = 0;
    this.simulateNotFoundCount = simulateNotFoundCount;
  }

  @Override
  public URL locate(String component, String path) {
    if (this.numRequests++ < this.simulateNotFoundCount) return null;

    this.locateRequests.addLast(new LocateRequest(component, path));

    try {
      return new URL("http://localhost");
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  void assertRequested(String expectedComponent, String expectedPath) {
    this.locateRequests.removeFirst().assertRequested(expectedComponent, expectedPath);
  }
  
  void assertDone() {
	assertTrue(this.locateRequests.isEmpty());
  }
  
  final class LocateRequest {
	private final String requestedComponent;
	private final String requestedPath;
	
	LocateRequest(String requestedComponent, String requestedPath) {
	  this.requestedComponent = requestedComponent;
	  this.requestedPath = requestedPath;
	}
	
	void assertRequested(String expectedComponent, String expectedPath) {
	   assertEquals(expectedComponent, this.requestedComponent);
	   assertEquals(expectedPath, this.requestedPath);
	}
  }
}
