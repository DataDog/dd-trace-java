import datadog.trace.api.Trace;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

  private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(2);

  @GET
  public void suspendThenResumeFromAnotherThread(@Suspended final AsyncResponse response) {
    // A short delay ensures the request thread has genuinely returned/suspended before
    // resume() is called, matching how a real background worker would behave (avoids a
    // resume-before-suspend-completes race in the test transport).
    EXECUTOR.schedule(
        () -> {
          doWorkOnBackgroundThread();
          response.resume("OK");
        },
        50,
        TimeUnit.MILLISECONDS);
  }

  @Trace
  private void doWorkOnBackgroundThread() {}
}
