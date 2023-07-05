package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Peer implements Encodable {

  private static final Logger log = LoggerFactory.getLogger(Peer.class);

  // We are limited in system-probe side, hence we can't increase this due to stack limit of ebpf
  // programs
  // Must be equal to
  // https://github.com/DataDog/datadog-agent/tree/main/pkg/network/ebpf/tls/java/types.h
  // (-1 as last byte is reserved for '\0')
  static final int MAX_DOMAIN_LENGTH = 47;
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
    log.debug("encoding peer domain: {} peer port: {}", domain, port);

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
