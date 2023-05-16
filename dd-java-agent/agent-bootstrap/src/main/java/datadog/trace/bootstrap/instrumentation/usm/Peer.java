package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Peer implements Encodable {

  private static final Logger log = LoggerFactory.getLogger(Peer.class);
  static final int MAX_DOMAIN_LENGTH = 63;
  private final String domain;
  private final int port;

  public Peer(String domain, int port) {
    this.domain = domain;
    this.port = port;
  }

  @Override
  public int size() {
    return MAX_DOMAIN_LENGTH + 1 + Short.BYTES;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    log.debug("encoding peer domain: " + domain + " peer port: " + port);

    buffer.putShort((short) port);

    // In rare cases the domain might be larger than 63 bytes
    int actualDomainLength = Integer.min(domain.length(), MAX_DOMAIN_LENGTH);
    int oldPos = buffer.position();
    buffer.put(domain.getBytes(StandardCharsets.UTF_8), 0, actualDomainLength);
    // advance buffer position to skip the pre-allocated 64 bytes for the domain (last byte is for
    // '\0')
    buffer.position(oldPos + MAX_DOMAIN_LENGTH + 1);
  }
}
