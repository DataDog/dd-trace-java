package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.message.AbstractHttpMessage;

/** Wraps HttpHost and HttpRequest into a HttpUriRequest for decorators and injectors */
public class HostAndRequestAsHttpUriRequest extends AbstractHttpMessage implements HttpUriRequest {

  private final String method;
  private final RequestLine requestLine;
  private final ProtocolVersion protocolVersion;
  private final java.net.URI uri;

  private final HttpRequest actualRequest;

  public HostAndRequestAsHttpUriRequest(final HttpHost httpHost, final HttpRequest httpRequest) {

    method = httpRequest.getRequestLine().getMethod();
    requestLine = httpRequest.getRequestLine();
    protocolVersion = requestLine.getProtocolVersion();
    uri = URIUtils.safeConcat(httpHost.toURI(), requestLine.getUri());
    actualRequest = httpRequest;
  }

  @Override
  public void abort() throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAborted() {
    return false;
  }

  @Override
  public void setHeader(final String name, final String value) {
    actualRequest.setHeader(name, value);
  }

  @Override
  public String getMethod() {
    return method;
  }

  @Override
  public RequestLine getRequestLine() {
    return requestLine;
  }

  @Override
  public ProtocolVersion getProtocolVersion() {
    return protocolVersion;
  }

  @Override
  public java.net.URI getURI() {
    return uri;
  }

  @Override
  public Header getFirstHeader(String name) {
    return actualRequest.getFirstHeader(name);
  }

  public HttpRequest getActualRequest() {
    return actualRequest;
  }
}
