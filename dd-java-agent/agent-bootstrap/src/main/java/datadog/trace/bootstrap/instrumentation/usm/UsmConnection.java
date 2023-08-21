package datadog.trace.bootstrap.instrumentation.usm;

import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UsmConnection {

  private static final Logger log = LoggerFactory.getLogger(UsmConnection.class);
  private InetAddress srcIp;
  private int srcPort;
  private InetAddress dstIp;
  private int dstPort;
  private boolean isIPv6;

  public UsmConnection(InetAddress src, int srcPort, InetAddress dst, int dstPort, boolean isIPv6) {
    this.srcIp = src;
    this.srcPort = srcPort;
    this.dstIp = dst;
    this.dstPort = dstPort;
    this.isIPv6 = isIPv6;
  }

  public InetAddress getSrcIP() {
    return srcIp;
  }

  public int getSrcPort() {
    return srcPort;
  }

  public InetAddress getDstIP() {
    return dstIp;
  }

  public int getDstPort() {
    return dstPort;
  }

  public boolean isIPV6() {
    return isIPv6;
  }
}
