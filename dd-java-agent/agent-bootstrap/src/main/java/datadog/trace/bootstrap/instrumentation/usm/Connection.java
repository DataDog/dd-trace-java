package datadog.trace.bootstrap.instrumentation.usm;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Connection implements Encodable {

  private static final Logger log = LoggerFactory.getLogger(Connection.class);

  /*
   Size of the connection struct:
      ______________________________________________________________________________________________________________________________________________
      |SrcIP [16 bytes] || DstIP [16 bytes] || Src Port [2 bytes] || Dst port [2 bytes] || Reserved [4 bytes] || Pid [4 bytes] || Metadata [4 bytes]|
      |_____________________________________________________________________________________________________________________________________________|
   As defined in https://github.com/DataDog/datadog-agent/blob/main/pkg/network/ebpf/c/conn_tuple.h
  */
  static final int CONNECTION_INFO_SIZE = 48;
  static final int IP_MAX_BYTES_LENGTH = 16;
  static final int IP_V4_BYTES_LENGTH = 4;

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
  /*
  Encodes the connection into a given buffer (Visitor pattern).
  */
  public void encode(ByteBuffer buffer) {
    log.debug("encoding connection:");
    log.debug("\tsrc host: {} src port: {}", srcIp, srcPort);
    if (dstIp != null) {
      log.debug("\tdst host: {} dst port: {}", dstIp, dstPort);
    }
    byte[] srcIPBuffer = srcIp.getAddress();
    // if IPv4 (4 bytes long), encode it into low part of the reserved space
    if (srcIPBuffer.length == IP_V4_BYTES_LENGTH) {
      buffer.putLong(0);
      buffer.put(srcIPBuffer, 0, srcIPBuffer.length);
      buffer.putInt(0);
    } else {
      buffer.put(srcIPBuffer, 0, srcIPBuffer.length);
    }

    if (dstIp != null) {
      byte[] dstIPBuffer = dstIp.getAddress();
      // if IPv4 (4 bytes long), encode it into low part of the reserved space
      if (dstIPBuffer.length == IP_V4_BYTES_LENGTH) {
        buffer.putLong(0);
        buffer.put(dstIPBuffer, 0, dstIPBuffer.length);
        buffer.putInt(0);
      } else {
        buffer.put(dstIPBuffer, 0, dstIPBuffer.length);
      }
    } else {
      // advance buffer position to skip the buffer
      buffer.position(buffer.position() + IP_MAX_BYTES_LENGTH);
    }

    // encode src and dst ports
    buffer.putShort((short) srcPort);
    buffer.putShort((short) dstPort);

    buffer.putInt(0);
    // use 0 for Pid, since we determine the Pid on the kernel side using the bpf
    // helper bpf_get_current_pid_tgid
    buffer.putInt(0);

    // we turn on the first bit - indicating it is a tcp connection
    int metadata = 1;
    if (isIPv6) {
      // turn on the 2nd bit indicating it is a ipv6 connection
      metadata |= 2;
    }
    buffer.putInt(metadata);
  }
}
