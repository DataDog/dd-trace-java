package datadog.trace.instrumentation.play26;

import java.net.InetAddress;
import java.security.cert.X509Certificate;
import play.api.mvc.request.RemoteConnection;
import scala.Option;
import scala.collection.Seq;

public class RemoteConnectionWithRawAddress implements RemoteConnection {
  private final RemoteConnection rawConnection;
  private final RemoteConnection delegate;

  public RemoteConnectionWithRawAddress(RemoteConnection rawConnection, RemoteConnection delegate) {
    this.rawConnection = rawConnection;
    this.delegate = delegate;
  }

  @Override
  public InetAddress remoteAddress() {
    return delegate.remoteAddress();
  }

  @Override
  public String remoteAddressString() {
    return delegate.remoteAddressString();
  }

  public String rawRemoteAddressString() {
    return rawConnection.remoteAddressString();
  }

  @Override
  public boolean secure() {
    return delegate.secure();
  }

  @Override
  public Option<Seq<X509Certificate>> clientCertificateChain() {
    return delegate.clientCertificateChain();
  }
}
