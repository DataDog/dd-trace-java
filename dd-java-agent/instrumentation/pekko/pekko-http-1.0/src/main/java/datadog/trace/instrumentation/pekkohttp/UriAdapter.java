package datadog.trace.instrumentation.pekkohttp;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import org.apache.pekko.http.scaladsl.model.Uri;
import scala.Option;

final class UriAdapter extends URIRawDataAdapter {

  private final Uri uri;

  UriAdapter(Uri uri) {
    this.uri = uri;
  }

  @Override
  public String scheme() {
    return uri.scheme();
  }

  @Override
  public String host() {
    return uri.authority().host().address();
  }

  @Override
  public int port() {
    return uri.authority().port();
  }

  @Override
  protected String innerRawPath() {
    return uri.path().toString();
  }

  @Override
  public String fragment() {
    return getOrElseNull(uri.fragment());
  }

  @Override
  protected String innerRawQuery() {
    return getOrElseNull(uri.rawQueryString());
  }

  private static String getOrElseNull(Option<String> optional) {
    if (optional.nonEmpty()) {
      return optional.get();
    }
    return null;
  }
}
