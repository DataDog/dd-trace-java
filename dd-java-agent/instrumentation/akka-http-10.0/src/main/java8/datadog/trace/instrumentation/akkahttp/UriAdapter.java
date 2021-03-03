package datadog.trace.instrumentation.akkahttp;

import akka.http.scaladsl.model.Uri;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import scala.Option;

final class UriAdapter implements URIDataAdapter {

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
  public String path() {
    return uri.path().toString();
  }

  @Override
  public String fragment() {
    return getOrElseNull(uri.fragment());
  }

  @Override
  public String query() {
    return getOrElseNull(uri.rawQueryString());
  }

  private static String getOrElseNull(Option<String> optional) {
    if (optional.nonEmpty()) {
      return optional.get();
    }
    return null;
  }
}
