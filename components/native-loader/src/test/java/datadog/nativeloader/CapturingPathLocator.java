package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Deque;
import java.util.LinkedList;

public final class CapturingPathLocator implements PathLocator {
  public static final boolean WITH_OMIT_COMP_FALLBACK = true;
  public static final boolean WITHOUT_OMIT_COMP_FALLBACK = false;

  public static final void testFailOnExceptions(
      LibraryResolver resolver,
      PlatformSpec platformSpec,
      boolean withSkipCompFallback,
      String... expectedPaths) {
    try {
      test(resolver, platformSpec, withSkipCompFallback, expectedPaths);
    } catch (Exception e) {
      throw new IllegalStateException("unexpected exception", e);
    }
  }

  public static final void test(
      LibraryResolver resolver,
      PlatformSpec platformSpec,
      boolean withSkipCompFallback,
      String... expectedPaths)
      throws Exception {
    String comp = "comp";

    CapturingPathLocator fullCaptureLocator = new CapturingPathLocator(Integer.MAX_VALUE);
    resolver.resolve(fullCaptureLocator, platformSpec, comp, "test");

    for (int i = 0; !fullCaptureLocator.isEmpty(); ++i) {
      if (i >= expectedPaths.length) {
        // checking the final fallback here was confusing when debugging tests

        if (!withSkipCompFallback) {
          fullCaptureLocator.assertDone();
        } else {
          // checked at at the end of the method
          fullCaptureLocator.nextRequest();
          fullCaptureLocator.assertDone();
        }
      } else {
        fullCaptureLocator.assertRequested(comp, expectedPaths[i]);
      }
    }

    for (int i = 0; i < expectedPaths.length; ++i) {
      CapturingPathLocator fallbackLocator = new CapturingPathLocator(i);
      resolver.resolve(fallbackLocator, platformSpec, comp, "test");

      for (int j = 0; j <= i; ++j) {
        fallbackLocator.assertRequested(comp, expectedPaths[j]);
      }
      fallbackLocator.assertDone();
    }

    if (withSkipCompFallback) {
      CapturingPathLocator fallbackLocator = new CapturingPathLocator(expectedPaths.length);
      resolver.resolve(fallbackLocator, platformSpec, comp, "test");

      for (int j = 0; j < expectedPaths.length; ++j) {
        fallbackLocator.assertRequested(comp, expectedPaths[j]);
      }
      fallbackLocator.assertRequested(null, expectedPaths[expectedPaths.length - 1]);
      fallbackLocator.assertDone();
    }
  }

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
  public URL locate(String optionalComponent, String path) {
    this.locateRequests.addLast(new LocateRequest(optionalComponent, path));

    if (this.numRequests++ < this.simulateNotFoundCount) return null;
    try {
      return new URL("http://localhost");
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  int numLocateRequests() {
    return this.locateRequests.size();
  }

  boolean isEmpty() {
    return this.locateRequests.isEmpty();
  }

  LocateRequest nextRequest() {
    return this.locateRequests.removeFirst();
  }

  void assertRequested(LocateRequest request) {
    this.assertRequested(request.requestedComponent, request.requestedPath);
  }

  void assertRequested(String expectedComponent, String expectedPath) {
    this.nextRequest().assertRequested(expectedComponent, expectedPath);
  }

  void assertDone() {
    // written this way to aid in debugging and fleshing out the test
    if (!this.isEmpty()) {
      this.assertRequested("", "");
    }
  }

  final class LocateRequest {
    private final String requestedComponent;
    private final String requestedPath;

    LocateRequest(String requestedComponent, String requestedPath) {
      this.requestedComponent = requestedComponent;
      this.requestedPath = requestedPath;
    }

    void assertRequested(String expectedComponent, String expectedPath) {
      // order is inverted, since usually test writer is worrying about comp directly
      assertEquals(expectedPath, this.requestedPath);
      assertEquals(expectedComponent, this.requestedComponent);
    }
  }
}
