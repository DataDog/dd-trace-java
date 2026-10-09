import datadog.trace.api.Trace;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

/**
 * The "textbook" async pattern, for regression coverage alongside {@link AsyncResumeResource}: the
 * resource method returns without resuming, and a different thread resumes it later. The GH-12597
 * fix must not change behavior on this path.
 */
@Path("/trueasyncresume")
public class TrueAsyncResumeResource {

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

  @GET
  public void suspendThenResumeFromAnotherThread(@Suspended final AsyncResponse response) {
    // The response is already suspended by the time this method runs (the container suspends
    // it as part of injecting the @Suspended parameter), so waiting on isSuspended() is a
    // no-op: it never blocks. Wait instead for the invoking (container) thread to actually
    // leave this method's stack frame, which can only happen after this method's own exit
    // advice has run (the advice is woven inside the frame, before the real return). That's a
    // deterministic proxy for "the resource method's exit advice has finished", which is the
    // ordering this test is meant to exercise.
    final Thread invokingThread = Thread.currentThread();
    EXECUTOR.submit(
        () -> {
          try {
            waitUntilThreadLeavesThisMethod(invokingThread);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          doWorkOnBackgroundThread();
          response.resume("OK");
        });
  }

  private static void waitUntilThreadLeavesThisMethod(final Thread invokingThread)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      final boolean stillOnStack =
          Arrays.stream(invokingThread.getStackTrace())
              .anyMatch(
                  frame ->
                      frame.getClassName().equals(TrueAsyncResumeResource.class.getName())
                          && frame.getMethodName().equals("suspendThenResumeFromAnotherThread"));
      if (!stillOnStack) {
        return;
      }
      Thread.sleep(1);
    }
  }

  @Trace
  private void doWorkOnBackgroundThread() {}
}
