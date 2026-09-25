import datadog.trace.api.Trace;
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
    EXECUTOR.submit(
        () -> {
          try {
            // Wait for the actual condition (the container has genuinely suspended the
            // response) instead of guessing a fixed delay, so this can't race under a slow
            // or overloaded test runner.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!response.isSuspended() && System.nanoTime() < deadline) {
              Thread.sleep(1);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          doWorkOnBackgroundThread();
          response.resume("OK");
        });
  }

  @Trace
  private void doWorkOnBackgroundThread() {}
}
