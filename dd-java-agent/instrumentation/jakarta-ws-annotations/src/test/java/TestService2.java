import jakarta.jws.WebService;

@WebService
public class TestService2 {
  public String send(final String request) {
    if ("fail".equals(request)) {
      throw new IllegalArgumentException("bad request");
    }
    return random();
  }

  protected String random() {
    return Double.toHexString(Math.random());
  }
}
