import datadog.trace.api.Trace;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

public class TestExceptionMapper implements ExceptionMapper<Throwable> {
  @Override
  @Trace
  public Response toResponse(Throwable exception) {
    return Response.ok(exception.getMessage()).build();
  }
}
