package datadog.trace.bootstrap.instrumentation.usmextractor;

import com.sun.jna.Pointer;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.ssl.SSLSocketImpl;

import java.net.Inet6Address;

public interface UsmMessage {

  enum MessageType{
    //message created from hooks on from read / write functions of AppInputStream and AppOutputStream respectively
    REQUEST,

    //message created from a hook on close method of the SSLSocketImpl
    CLOSE_CONNECTION,
  }

  enum ProtocolType{
    //message created from hooks on from read / write functions of AppInputStream and AppOutputStream respectively
    IPv4,

    //message created from a hook on close method of the SSLSocketImpl
    IPv6,
  }

  //TODO: sync with systemprobe code
  static final NativeLong USM_IOCTL_ID = new NativeLong(0xda7ad09L);;

  Pointer getBufferPtr();

  int dataSize();
  boolean validate();

  abstract class BaseUsmMessage implements UsmMessage{

    private static final Logger log = LoggerFactory.getLogger(BaseUsmMessage.class);

    //Message type [1 byte] || Total message size  [4 bytes]
    static final int HEADER_SIZE = 5;

    // size of the connection struct:
    // Connection Info length [4 bytes] || Protocol Type [1 byte] || Src IP [4 bytes] || Src Port [4 bytes] || Dst IP [4 bytes] || Dst port [4 bytes]
    static final int CONNECTION_INFO_SIZE = 21;

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
      offset+=1;

      //write totalMessageSize
      pointer.setInt(offset,totalMessageSize);
      offset+=4;

      encodeConnection(socket);
    }

    private void encodeConnection(SSLSocketImpl socket){
      ProtocolType protocolType = ProtocolType.IPv4;
      if (socket.getLocalAddress() instanceof Inet6Address){
        protocolType = ProtocolType.IPv6;
      }

      //write connection length
      pointer.setInt(offset,CONNECTION_INFO_SIZE-4);
      offset+=4;

      //write protocol type
      pointer.setByte(offset,(byte)protocolType.ordinal());
      offset+=1;


      //TODO: add support to IPv6
      //write local ip + port
      pointer.write(offset,socket.getLocalAddress().getAddress(),0,4);
      offset += 4;
      pointer.setInt(offset, socket.getLocalPort());
      offset += 4;

      //write remote ip + port
      pointer.write(offset,socket.getInetAddress().getAddress(),0,4);
      offset += 4;
      pointer.setInt(offset, socket.getPeerPort());
      offset += 4;
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
        offset += 4;

        pointer.write(offset,buffer,bufferOffset,len);
        offset+=len;

      }
      // if it is, use only max allowed bytes
      else{
        pointer.setInt(offset,MAX_HTTPS_BUFFER_SIZE);
        offset += 4;
        pointer.write(offset,buffer,bufferOffset,MAX_HTTPS_BUFFER_SIZE);
        offset += MAX_HTTPS_BUFFER_SIZE;
      }
    }

    @Override
    public int dataSize() {
      //max buffer preceded by the actual length [4 bytes] of the buffer
      return MAX_HTTPS_BUFFER_SIZE+4;
    }
  }

}
