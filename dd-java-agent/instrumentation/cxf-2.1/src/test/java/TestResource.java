import datadog.trace.api.Trace;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

@Path("/test")
public class TestResource {
  @GET
  public void someService(@Suspended final AsyncResponse response) {
    doSomething();
    response.resume(new RuntimeException("Failure"));
  }

  @Trace
  private void doSomething() {}
}
