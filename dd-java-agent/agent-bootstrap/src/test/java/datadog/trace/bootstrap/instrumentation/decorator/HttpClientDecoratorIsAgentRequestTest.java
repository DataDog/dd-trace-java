package datadog.trace.bootstrap.instrumentation.decorator;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;

class HttpClientDecoratorIsAgentRequestTest {

  // Regression test: advice runs before the instrumented method's own argument validation, so a
  // caller passing a null request (e.g. HttpClient.sendAsync(null, ...)) must not NPE inside
  // isAgentRequest -> getRequestHeader. See
  // datadog.trace.instrumentation.httpclient.SendAsyncAdvice.
  @Test
  void isAgentRequestOfNullRequestReturnsFalseInsteadOfThrowing() {
    HttpClientDecorator<Object, Object> decorator =
        new HttpClientDecorator<Object, Object>() {
          @Override
          protected String[] instrumentationNames() {
            return new String[] {"test"};
          }

          @Override
          protected CharSequence component() {
            return "test-component";
          }

          @Override
          protected String method(Object request) {
            return null;
          }

          @Override
          protected URI url(Object request) throws URISyntaxException {
            return null;
          }

          @Override
          protected int status(Object response) {
            return 0;
          }

          @Override
          protected String getRequestHeader(Object request, String headerName) {
            throw new NullPointerException("should not be reached for a null request");
          }

          @Override
          protected String getResponseHeader(Object response, String headerName) {
            return null;
          }
        };

    assertFalse(decorator.isAgentRequest(null));
  }
}
