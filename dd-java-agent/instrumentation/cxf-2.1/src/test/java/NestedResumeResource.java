import datadog.trace.api.Trace;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

/**
 * Regression coverage for a gap found in code review of GH-12597's fix: {@code resume()} called
 * from a {@code @Trace}-annotated helper, not directly from the resource method's own body. At the
 * moment {@code resume()} runs, the helper's own span is the active one, not the resource method's
 * -- an early version of the fix compared {@code activeSpan()} directly against the resource
 * method's span and wrongly treated this as a genuinely-async call.
 */
@Path("/nestedresume")
public class NestedResumeResource {
  @GET
  public void resumeViaHelper(@Suspended final AsyncResponse response) {
    resumeFromHelper(response);
  }

  @Trace
  private void resumeFromHelper(final AsyncResponse response) {
    response.resume("OK");
  }
}
