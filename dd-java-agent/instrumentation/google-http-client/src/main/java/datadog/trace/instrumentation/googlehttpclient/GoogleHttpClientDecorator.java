package datadog.trace.instrumentation.googlehttpclient;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public class GoogleHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  private static final Pattern URL_REPLACEMENT = Pattern.compile("%20");
  public static final CharSequence GOOGLE_HTTP_CLIENT =
      UTF8BytesString.createConstant("google-http-client");
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.createConstant("http.request");
  public static final GoogleHttpClientDecorator DECORATE = new GoogleHttpClientDecorator();

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.getRequestMethod();
  }

  @Override
  protected URI url(final HttpRequest httpRequest) throws URISyntaxException {
    // Google uses %20 (space) instead of "+" for spaces in the fragment
    // Add "+" back for consistency with the other http client instrumentations
    final String url = httpRequest.getUrl().build();
    final String fixedUrl = URL_REPLACEMENT.matcher(url).replaceAll("+");
    return new URI(fixedUrl);
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getStatusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"google-http-client"};
  }

  @Override
  protected CharSequence component() {
    return GOOGLE_HTTP_CLIENT;
  }
}
