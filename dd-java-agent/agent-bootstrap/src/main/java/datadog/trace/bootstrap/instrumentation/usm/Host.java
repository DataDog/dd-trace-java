package datadog.trace.bootstrap.instrumentation.usm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public final class Host implements Encodable {

  private static final Logger log = LoggerFactory.getLogger(Host.class);
  static final int MAX_HOSTNAME_SIZE = 64;
  private final String peerDomain;
  private final int peerPort;

  public Host(String peerDomain, int peerPort) {
    this.peerDomain = peerDomain;
    this.peerPort = peerPort;
  }

  @Override
  public int size() {
    return MAX_HOSTNAME_SIZE;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    log.debug("encoding peer domain: "
          + peerDomain
          + " peer port: "
          + peerPort);
    buffer.put(peerDomain.getBytes(), 0, Integer.min(peerDomain.length(),MAX_HOSTNAME_SIZE));
    buffer.putShort((short) peerPort);
  }
}
