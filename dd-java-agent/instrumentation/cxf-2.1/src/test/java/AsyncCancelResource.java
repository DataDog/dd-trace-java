import datadog.trace.api.Trace;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

/**
 * Same GH-12597 pattern as {@link AsyncResumeResource}, but exercising {@code
 * AsyncResponse#cancel()} instead of {@code resume()} -- the third advice touched by the fix
 * ({@code AsyncResponseCancelAdvice}).
 */
@Path("/asynccancel")
public class AsyncCancelResource {
  @GET
  public void cancelThenWork(@Suspended final AsyncResponse response) {
    response.cancel();
    doWorkAfterCancel();
  }

  @Trace
  private void doWorkAfterCancel() {}
}
