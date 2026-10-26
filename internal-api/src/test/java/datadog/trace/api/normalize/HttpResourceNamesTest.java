package datadog.trace.api.normalize;

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_CLIENT_CONFIG_PATTERN_MATCH;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class HttpResourceNamesTest extends DDJavaSpecification {

  @BeforeEach
  void setup() {
    HttpResourceNames.reset();
  }

  @Test
  void usesTheSimpleNormalizerForServersByDefault() {
    AgentSpan span = mock(AgentSpan.class);

    HttpResourceNames.setForServer(span, "GET", "/asdf/1234", false);

    verify(span).setResourceName(UTF8BytesString.create("GET /asdf/?"), HTTP_PATH_NORMALIZER);
  }

  @Test
  @WithConfig(key = TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, value = "/asdf/*:/test")
  void usesTheAntMatchingNormalizerForServersWhenConfiguredTo() {
    AgentSpan span = mock(AgentSpan.class);

    HttpResourceNames.setForServer(span, "GET", "/asdf/1234", false);

    verify(span)
        .setResourceName(UTF8BytesString.create("GET /test"), HTTP_SERVER_CONFIG_PATTERN_MATCH);
  }

  @Test
  void usesTheSimpleNormalizerForClientsByDefault() {
    AgentSpan span = mock(AgentSpan.class);

    HttpResourceNames.setForClient(span, "GET", "/asdf/1234", false);

    verify(span).setResourceName(UTF8BytesString.create("GET /asdf/?"), HTTP_PATH_NORMALIZER);
  }

  @Test
  @WithConfig(key = TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, value = "/asdf/*:/test")
  void usesTheAntMatchingNormalizerForClientsWhenConfiguredTo() {
    AgentSpan span = mock(AgentSpan.class);

    HttpResourceNames.setForClient(span, "GET", "/asdf/1234", false);

    verify(span)
        .setResourceName(UTF8BytesString.create("GET /test"), HTTP_CLIENT_CONFIG_PATTERN_MATCH);
  }

  @TableTest({
    "scenario        | method | path      | expected     ",
    "method and path | GET    | /test     | GET /test    ",
    "no method       |        | /test     | /test        ",
    "no path         | GET    |           | /            ",
    "empty path      | GET    | ''        | 'GET '       ",
    "root path       | GET    | /         | GET /        ",
    "double slash    | GET    | //        | GET //       ",
    "nested path     | GET    | /foo/bar  | GET /foo/bar ",
    "trailing slash  | GET    | /foo/bar/ | GET /foo/bar/"
  })
  void worksAsExpected(String method, String path, String expected) {
    CharSequence resourceName = HttpResourceNames.join(method, path);

    assertEquals(expected, resourceName.toString());
  }

  @TableTest({
    "scenario                  | method | path      | expected    ",
    "nested trailing slash     | GET    | /foo/bar/ | GET /foo/bar",
    "no trailing slash         | GET    | /test     | GET /test   ",
    "trailing slash            | GET    | /test/    | GET /test   ",
    "no method                 |        | /test     | /test       ",
    "no method, trailing slash |        | /test/    | /test       ",
    "no path                   | GET    |           | /           ",
    "empty path                | GET    | ''        | 'GET '      ",
    "root path                 | GET    | /         | GET /       ",
    "double slash              | GET    | //        | GET /       "
  })
  @WithConfig(key = TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH, value = "true")
  void removeTrailingSlash(String method, String path, String expected) {
    CharSequence resourceName = HttpResourceNames.join(method, path);

    assertEquals(expected, resourceName.toString());
  }
}
