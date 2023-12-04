import datadog.trace.api.Trace;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

public class TestExceptionMapper implements ExceptionMapper<Throwable> {
  @Override
  @Trace
  public Response toResponse(Throwable exception) {
    return Response.ok(exception.getMessage()).build();
  }
}
