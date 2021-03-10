import javax.xml.ws.Provider;
import javax.xml.ws.WebServiceProvider;

@WebServiceProvider
public class TestProvider implements Provider<String> {
  @Override
  public String invoke(final String request) {
    if ("fail".equals(request)) {
      throw new IllegalArgumentException("bad request");
    }
    return random();
  }

  protected String random() {
    return Double.toHexString(Math.random());
  }
}
