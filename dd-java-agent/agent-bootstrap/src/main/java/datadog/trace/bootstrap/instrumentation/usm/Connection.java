package datadog.trace.bootstrap.instrumentation.usm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.ByteBuffer;

public final class Connection implements Encodable {

  private static final Logger log = LoggerFactory.getLogger(Connection.class);

  // size of the connection struct:
  // SrcIP [16 bytes] || DstIP [16 bytes] || Src Port [2 bytes] || Dst port [2
  // bytes] || Reserved [4 bytes] || Pid [4 bytes] || Metadata [4 bytes]
  static final int CONNECTION_INFO_SIZE = 48;
  static final int IP_LENGTH = 16;

  private final InetAddress srcIp;
  private final int srcPort;
  private final InetAddress dstIp;
  private final int dstPort;
  private final boolean isIPv6;

  public Connection(InetAddress src, int srcPort, InetAddress dst, int dstPort, boolean isIPv6) {
    this.srcIp = src;
    this.srcPort = srcPort;
    this.dstIp = dst;
    this.dstPort = dstPort;
    this.isIPv6 = isIPv6;
  }

  @Override
  public int size() {
    return CONNECTION_INFO_SIZE;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    log.debug("encoding connection:");
    log.debug(
        "\tsrc host: "
            + srcIp.toString()
            + " src port: "
            + srcPort);
    log.debug(
        "\tdst host: "
            + dstIp.toString()
            + " dst port: "
            + dstPort);
    byte[] srcIPBuffer = srcIp.getAddress();
    // if IPv4 (4 bytes long), encode it into low part of the reserved space
    if (srcIPBuffer.length == 4) {
      buffer.putLong(0);
      buffer.put(srcIPBuffer, 0, srcIPBuffer.length);
      buffer.putInt(0);
    } else {
      buffer.put(srcIPBuffer, 0, srcIPBuffer.length);
    }

    byte[] dstIPBuffer = dstIp.getAddress();
    // if IPv4 (4 bytes long), encode it into low part of the reserved space
    if (dstIPBuffer.length == 4) {
      buffer.putLong(0);
      buffer.put(dstIPBuffer, 0, dstIPBuffer.length);
      buffer.putInt(0);
    } else {
      buffer.put(dstIPBuffer, 0, dstIPBuffer.length);
    }

    // encode src and dst ports
    buffer.putShort((short) srcPort);
    buffer.putShort((short) dstPort);

    buffer.putInt( 0);
    // use 0 for Pid, since we determine the Pid on the kernel side using the bpf
    // helper bpf_get_current_pid_tgid
    buffer.putInt( 0);

    // we turn on the first bit - indicating it is a tcp connection
    int metadata = 1;
    if (isIPv6) {
      // turn on the 2nd bit indicating it is a ipv6 connection
      metadata |= 2;
    }
    buffer.putInt(metadata);
  }
}
