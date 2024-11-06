public class TestService1Impl implements TestService1 {
  @Override
  public String send(final String request) {
    if ("fail".equals(request)) {
      throw new IllegalArgumentException("bad request");
    }
    return random();
  }

  public String random() {
    return Double.toHexString(Math.random());
  }
}
