package datadog.trace.bootstrap.instrumentation.usmextractor;

import com.sun.jna.Pointer;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.ssl.SSLSocketImpl;
//import datadog.common.process.PidHelper;

import java.net.Inet4Address;
import java.net.Inet6Address;

public interface UsmMessage {

  enum MessageType{
    //message created from hooks on from read / write functions of AppInputStream and AppOutputStream respectively
    REQUEST,

    //message created from a hook on close method of the SSLSocketImpl
    CLOSE_CONNECTION,
  }

  //TODO: sync with systemprobe code
  static final NativeLong USM_IOCTL_ID = new NativeLong(0xda7ad09L);;

  Pointer getBufferPtr();

  int dataSize();
  boolean validate();

  abstract class BaseUsmMessage implements UsmMessage{

    private static final Logger log = LoggerFactory.getLogger(BaseUsmMessage.class);

    //Message type [1 byte]
    static final int HEADER_SIZE = 1;

    // size of the connection struct:
    // SrcIP [16 bytes] || DstIP [16 bytes] || Src Port [2 bytes] || Dst port [2 bytes] || Reserved [4 bytes] || Pid [4 bytes] || Metadata [4 bytes]
    static final int CONNECTION_INFO_SIZE = 48;

    //pointer to native memory buffer
    protected Pointer pointer;

    protected int offset;
    private MessageType messageType;
    private int totalMessageSize;

    @Override
    public final Pointer getBufferPtr(){
      return pointer;
    }

    @Override
    public boolean validate() {
      if (offset != getMessageSize()){
        log.warn(String.format("invalid message size, expected: %d actual: %d",getMessageSize(), offset));
        return false;
      }
      return true;
    }

    private int getMessageSize(){
      return totalMessageSize;
    }

    public BaseUsmMessage(MessageType type, SSLSocketImpl socket){
      messageType = type;

      totalMessageSize = HEADER_SIZE + CONNECTION_INFO_SIZE + dataSize();
      pointer = new Memory(totalMessageSize);
      offset = 0;

      //encode message type
      pointer.setByte(offset,(byte)messageType.ordinal());
      offset+=Byte.BYTES;

      encodeConnection(socket);
    }

    private void encodeConnection(SSLSocketImpl socket){

      //we reserve 16 bytes for src IP (in case it is IPv6)
      byte[] srcIPBuffer = socket.getLocalAddress().getAddress();
      pointer.write(offset,srcIPBuffer,0,srcIPBuffer.length);
      offset += 16;

      //we reserve 16 bytes for dst IP (in case it is IPv6)
      byte[] dstIPBuffer = socket.getInetAddress().getAddress();
      pointer.write(offset,dstIPBuffer,0,dstIPBuffer.length);
      offset += 16;

      //encode src and dst ports
      pointer.setShort(offset, (short)socket.getLocalPort());
      offset += Short.BYTES;
      pointer.setShort(offset, (short)socket.getPeerPort());
      offset += Short.BYTES;

      //we put 0 as netns
      pointer.setInt(offset, 0);
      offset += Integer.BYTES;
      //encode Pid
      pointer.setInt(offset, 0);
      //TODO: uncomment after rebasing with main branch, as PidHelper was moved under internal-api
      //pointer.setInt(offset, PidHelper.PID.intValue());
      offset += Integer.BYTES;

      //we turn on the first bit - indicating it is a tcp connection
      int metadata = 1;
      if (socket.getLocalAddress() instanceof Inet6Address){
        //turn on the 2nd bit indicating it is a ipv6 connection
        metadata |= 2;
      }
      pointer.setInt(offset, metadata);
      offset += Integer.BYTES;
    }
  }

  class CloseConnectionUsmMessage extends BaseUsmMessage{

    public CloseConnectionUsmMessage(SSLSocketImpl socket) {
      super(MessageType.CLOSE_CONNECTION, socket);
    }

    @Override
    public int dataSize() {
      //no actual data for closed connection message, only the connection tuple
      return 0;
    }
  }

  class RequestUsmMessage extends BaseUsmMessage{

    // This determines the size of the payload fragment that is captured for each HTTPS request
    //should be equal to:  https://github.com/DataDog/datadog-agent/blob/main/pkg/network/ebpf/c/protocols/http-types.h#L7
    static final int MAX_HTTPS_BUFFER_SIZE = 8 * 20;
    public RequestUsmMessage(SSLSocketImpl socket, byte[] buffer, int bufferOffset, int len) {
      super(MessageType.REQUEST, socket);

      // check the buffer is not larger than max allowed,
      if (len - bufferOffset <= MAX_HTTPS_BUFFER_SIZE){

        pointer.setInt(offset,len);
        offset += Integer.BYTES;

        pointer.write(offset,buffer,bufferOffset,len);
        offset+=len;

      }
      // if it is, use only max allowed bytes
      else{
        pointer.setInt(offset,MAX_HTTPS_BUFFER_SIZE);
        offset += Integer.BYTES;
        pointer.write(offset,buffer,bufferOffset,MAX_HTTPS_BUFFER_SIZE);
        offset += MAX_HTTPS_BUFFER_SIZE;
      }
    }

    @Override
    public int dataSize() {
      //max buffer preceded by the actual length [4 bytes] of the buffer
      return MAX_HTTPS_BUFFER_SIZE+Integer.BYTES;
    }
  }

}
