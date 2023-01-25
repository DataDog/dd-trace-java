package datadog.trace.agent.tooling;

import com.sun.jna.Pointer;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.ssl.SSLSocketImpl;

import java.net.Inet4Address;
import java.net.Inet6Address;

import datadog.trace.bootstrap.instrumentation.api.UsmMessage;

public abstract class UsmMessageImpl {
  enum MessageType {
    // message created from hooks on from read / write functions of AppInputStream
    // and AppOutputStream respectively
    REQUEST,

    // message created from a hook on close method of the SSLSocketImpl
    CLOSE_CONNECTION,
  }

  private static final Logger log = LoggerFactory.getLogger(BaseUsmMessage.class);

  // TODO: sync with systemprobe code
  static final NativeLong USM_IOCTL_ID = new NativeLong(0xda7ad09L);;

  static abstract class BaseUsmMessage implements UsmMessage {

    // Message type [1 byte]
    static final int HEADER_SIZE = 1;

    // size of the connection struct:
    // SrcIP [16 bytes] || DstIP [16 bytes] || Src Port [2 bytes] || Dst port [2
    // bytes] || Reserved [4 bytes] || Pid [4 bytes] || Metadata [4 bytes]
    static final int CONNECTION_INFO_SIZE = 48;

    // pointer to native memory buffer
    protected Pointer pointer;

    protected int offset;
    private MessageType messageType;
    private int totalMessageSize;

    public final Pointer getBufferPtr() {
      return pointer;
    }

    @Override
    public boolean validate() {
      if (offset > getMessageSize()) {
        log.warn(String.format("invalid message size, expected: %d actual: %d", getMessageSize(), offset));
        return false;
      }
      return true;
    }

    private int getMessageSize() {
      return totalMessageSize;
    }

    public BaseUsmMessage(MessageType type, SSLSocketImpl socket) {
      messageType = type;

      totalMessageSize = HEADER_SIZE + CONNECTION_INFO_SIZE + dataSize();
      pointer = new Memory(totalMessageSize);
      pointer.clear(totalMessageSize);
      offset = 0;

      // encode message type
      pointer.setByte(offset, (byte) messageType.ordinal());
      offset += Byte.BYTES;

      encodeConnection(socket);
    }

    private void encodeConnection(SSLSocketImpl socket) {

      // we reserve 2 long for ip, as IPv6 takes 128 bytes
      int ipReservedSize = Long.BYTES * 2;
      byte[] srcIPBuffer = socket.getLocalAddress().getAddress();
      //if IPv4 (4 bytes long), encode it into low part of the reserved space
      if (srcIPBuffer.length == 4){
        pointer.write(offset+Long.BYTES, srcIPBuffer, 0, srcIPBuffer.length);
      }else{
        pointer.write(offset, srcIPBuffer, 0, srcIPBuffer.length);
      }
      offset += ipReservedSize;

      byte[] dstIPBuffer = socket.getInetAddress().getAddress();
      //if IPv4 (4 bytes long), encode it into low part of the reserved space
      if (dstIPBuffer.length == 4){
        pointer.write(offset+Long.BYTES, dstIPBuffer, 0, dstIPBuffer.length);
      }else{
        pointer.write(offset, dstIPBuffer, 0, dstIPBuffer.length);
      }

      offset += ipReservedSize;

      // encode src and dst ports
      pointer.setShort(offset, (short) socket.getLocalPort());
      offset += Short.BYTES;
      pointer.setShort(offset, (short) socket.getPeerPort());
      offset += Short.BYTES;

      // we put 0 as netns
      pointer.setInt(offset, 0);
      offset += Integer.BYTES;
      // use 0 for Pid, since we determine the Pid on the kernel side using the bpf
      // helper bpf_get_current_pid_tgid
      pointer.setInt(offset, 0);
      offset += Integer.BYTES;

      // we turn on the first bit - indicating it is a tcp connection
      int metadata = 1;
      if (socket.getLocalAddress() instanceof Inet6Address) {
        // turn on the 2nd bit indicating it is a ipv6 connection
        metadata |= 2;
      }
      pointer.setInt(offset, metadata);
      offset += Integer.BYTES;
    }
  }

  static class CloseConnectionUsmMessage extends BaseUsmMessage {

    public CloseConnectionUsmMessage(SSLSocketImpl socket) {
      super(MessageType.CLOSE_CONNECTION, socket);
      log.debug("close socket:");
      log.debug("src host: " + socket.getLocalAddress().toString() + " src port: " + socket.getLocalPort());
      log.debug("dst host: " + socket.getInetAddress().toString() + " dst port: " + socket.getPeerPort());
    }

    @Override
    public int dataSize() {
      // no actual data for closed connection message, only the connection tuple
      return 0;
    }
  }

  static class RequestUsmMessage extends BaseUsmMessage {

    // This determines the size of the payload fragment that is captured for each
    // HTTPS request
    // should be equal to:
    // https://github.com/DataDog/datadog-agent/blob/main/pkg/network/ebpf/c/protocols/http-types.h#L7
    static final int MAX_HTTPS_BUFFER_SIZE = 8 * 20;

    public RequestUsmMessage(SSLSocketImpl socket, byte[] buffer, int bufferOffset, int len) {
      super(MessageType.REQUEST, socket);

      log.debug("Request packet:");
      log.debug("src host: " + socket.getLocalAddress().toString() + " src port: " + socket.getLocalPort());
      log.debug("dst host: " + socket.getInetAddress().toString() + " dst port: " + socket.getPeerPort());
      log.debug("intercepted byte len: " + len);

      // check the buffer is not larger than max allowed,
      if (len - bufferOffset <= MAX_HTTPS_BUFFER_SIZE) {
        pointer.setInt(offset, len);
        offset += Integer.BYTES;

        pointer.write(offset, buffer, bufferOffset, len);
        offset += len;

      }
      // if it is, use only max allowed bytes
      else {
        pointer.setInt(offset, MAX_HTTPS_BUFFER_SIZE);
        offset += Integer.BYTES;
        pointer.write(offset, buffer, bufferOffset, MAX_HTTPS_BUFFER_SIZE);
        offset += MAX_HTTPS_BUFFER_SIZE;
      }
    }

    @Override
    public int dataSize() {
      // max buffer preceded by the actual length [4 bytes] of the buffer
      return MAX_HTTPS_BUFFER_SIZE + Integer.BYTES;
    }
  }

}
