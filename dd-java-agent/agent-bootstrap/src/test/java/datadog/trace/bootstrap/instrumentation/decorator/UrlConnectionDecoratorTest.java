package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.net.URI;
import java.net.URISyntaxException;
import org.tabletest.junit.TableTest;

class UrlConnectionDecoratorTest extends ClientDecoratorTest {

  @TableTest({
    "scenario                 | url                                  | expectedUrl                          | hostname | port",
    "zero port                | 'https://host:0'                     | 'https://host:0'                     | host     |     ",
    "path                     | 'https://host/path'                  | 'https://host/path'                  | host     |     ",
    "port, query and fragment | 'http://host:99/path?query#fragment' | 'http://host:99/path?query#fragment' | host     | 99  "
  })
  void testUrlHandling(String url, String expectedUrl, String hostname, Integer port)
      throws URISyntaxException {
    UrlConnectionDecorator decorator = newDecorator();

    // when
    decorator.onURI(span, new URI(url));

    // then
    if (expectedUrl != null) {
      verify(span).setTag(HTTP_URL, expectedUrl);
    }
    if (hostname != null) {
      verify(span).setTag(PEER_HOSTNAME, hostname);
    }
    if (port != null) {
      verify(span).setTag(PEER_PORT, (int) port);
    }
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario | protocol | expectedOperationName",
    "http     | http     | http.request         ",
    "ftp      | ftp      | ftp.request          ",
    "file     | file     | file.request         "
  })
  void testOperationNameForProtocol(String protocol, String expectedOperationName) {
    UrlConnectionDecorator decorator = newDecorator();

    // when
    CharSequence operationName = decorator.operationName(protocol);

    // then
    assertInstanceOf(UTF8BytesString.class, operationName);
    assertEquals(expectedOperationName, operationName.toString());
  }

  @Override
  protected UrlConnectionDecorator newDecorator() {
    return new UrlConnectionDecorator() {
      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1", "test2"};
      }

      @Override
      protected CharSequence spanType() {
        return DDSpanTypes.TEST;
      }

      @Override
      protected String service() {
        return null;
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      protected boolean traceAnalyticsDefault() {
        return true;
      }
    };
  }
}
