package mule4;

public class HttpServerTestHandler {

  public Response handle(String requestPath) {
    return new Response(testHandle(requestPath));
  }

  // This method will be instrumented to do a call to the actual test code, so we
  // can circumvent the rigid class loader isolation in Mule
  public static Object[] testHandle(String requestPath) {
    return new Object[] {"Unavailable for failing test instrumentation: " + requestPath, 451};
  }

  public static final class Response {
    private final String body;
    private final int status;

    public Response(String body, int status) {
      this.body = body;
      this.status = status;
    }

    public Response(Object[] response) {
      this(String.valueOf(response[0]), (Integer) response[1]);
    }

    @Override
    public String toString() {
      return "Response [body=" + body + ", status=" + status + "]";
    }

    public String getBody() {
      return body;
    }

    public int getCode() {
      return status;
    }
  }
}
