package datadog.trace.bootstrap.instrumentation.usm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.Buffer;
import java.nio.ByteBuffer;

//This is a utility that allows to serialize different messages for eRPC protocol (over ioctl) between USM instrumentation classes and SystemProbe
public class MessageEncoder {
  private static final Logger log = LoggerFactory.getLogger(MessageEncoder.class);
  public enum MessageType {
    // message created from hooks on read / write functions of AppInputStream
    // and AppOutputStream respectively and contains the actual payload and the connection information
    // (used by SSLSocket instrumentation)
    REQUEST,

    // message created when an underlying socket is closed
    // (used by SocketChannel and SSLSocket instrumentations)
    CLOSE_CONNECTION,

    // message created by the transport layer of async frameworks (e.g: SocketChannel)
    // to allow correlation between the tuple of peer domain and peer port against the actual connection
    // (used by SocketChannelImpl instrumentation)
    HOSTNAME,

    // message created by the SSL encryption layer of async frameworks (SSLEngine)
    // and contains the peer domain and port information and the actual payload
    // (used by SSLEngine instrumentation)
    PLAIN,
  }

  public static Buffer encode(MessageType type, Encodable... entities){
    int size=1; //for the message type

    //calculate the full size of the buffer we need to allocate
    for (Encodable entity:entities){
      size+=entity.size();
    }

    //allocate and initialize the buffer, it must be a direct buffer as we need to pass the pointer via ioctl
    ByteBuffer buffer = ByteBuffer.allocateDirect(size);
    buffer.clear();

    log.debug("encoding " + type + " message of size  " + size);

    //encode message type
    buffer.put((byte)type.ordinal());

    //encode each entity using a visitor pattern to avoid extra buffer allocations
    for (Encodable entity:entities){
      entity.encode(buffer);
    }

    return buffer;
  }
}
