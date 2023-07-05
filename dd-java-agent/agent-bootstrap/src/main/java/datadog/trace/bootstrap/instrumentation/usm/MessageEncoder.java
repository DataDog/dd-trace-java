package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This is a utility that allows to serialize different messages for eRPC protocol (over ioctl)
// between USM instrumentation classes and SystemProbe
public class MessageEncoder {
  private static final Logger log = LoggerFactory.getLogger(MessageEncoder.class);

  // Java enums and especially enum switches are really bloated, using directly byte types

  // message created from hooks on read / write functions of AppInputStream
  // and AppOutputStream respectively and contains the actual payload and the connection
  // information
  // (used by SSLSocket instrumentation)
  public static final byte SYNCHRONOUS_PAYLOAD = 0;
  // message created when an underlying socket is closed
  // (used by SocketChannel and SSLSocket instrumentations)
  public static final byte CLOSE_CONNECTION = 1;
  // message created by the transport layer of async frameworks (e.g: SocketChannel)
  // to allow correlation between the tuple of peer domain and peer port against the actual
  // connection
  // (used by SocketChannel instrumentation)
  public static final byte CONNECTION_BY_PEER = 2;

  // message created by the SSL encryption layer of async frameworks (SSLEngine)
  // and contains the peer domain and port information and the actual payload
  // (used by SSLEngine instrumentation)
  public static final byte ASYNC_PAYLOAD = 3;

  public static Buffer encode(byte type, Encodable... entities) {
    int size = 1; // for the message type

    // calculate the full size of the buffer we need to allocate to encode the full message with all
    // the entities
    for (Encodable entity : entities) {
      size += entity.size();
    }

    // allocate and initialize the buffer, it MUST be a direct buffer as we need to pass the pointer
    // via ioctl
    ByteBuffer buffer = ByteBuffer.allocateDirect(size);
    // Set the byte order to little-endian (to allow proper decoding in eBPF code in SystemProbe
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    // zero the buffer content
    buffer.clear();
    log.debug("encoding {} message of size {}", type, size);

    // encode message type
    buffer.put(type);

    // encode each entity using a visitor pattern to avoid extra buffer allocations
    for (Encodable entity : entities) {
      entity.encode(buffer);
    }

    return buffer;
  }
}
