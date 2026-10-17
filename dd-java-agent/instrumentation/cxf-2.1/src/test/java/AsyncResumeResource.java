import datadog.trace.api.Trace;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

@Path("/asyncresume")
public class AsyncResumeResource {
  @GET
  public void resumeThenWork(@Suspended final AsyncResponse response) {
    response.resume("OK");
    doWorkAfterResume();
  }

  @Trace
  private void doWorkAfterResume() {}
}
