import datadog.trace.api.Trace;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;

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
