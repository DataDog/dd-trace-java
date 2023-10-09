package datadog.trace.instrumentation.play26;

import java.net.InetAddress;
import java.security.cert.X509Certificate;
import play.api.mvc.request.RemoteConnection;
import scala.Option;
import scala.collection.Seq;

/** @see SavePeerAddressInstrumentation */
public class RemoteConnectionWithRawAddress implements RemoteConnection {
  private final RemoteConnection delegate;
  private final InetAddress originalAddress;

  public RemoteConnectionWithRawAddress(RemoteConnection delegate, InetAddress originalAddress) {
    this.delegate = delegate;
    this.originalAddress = originalAddress;
  }

  @Override
  public InetAddress remoteAddress() {
    return delegate.remoteAddress();
  }

  public InetAddress originalRemoteAddress() {
    return originalAddress;
  }

  @Override
  public String remoteAddressString() {
    return delegate.remoteAddressString();
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
