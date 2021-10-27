package datadog.cws.erpc;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

/**
 * This class represents a CWS eRPC request.
 *
 * <p>A request is made of one op code of 8 bits + 256 bytes of data.
 */
public class Request {
  static final int OP_CODE_SIZE = 1;
  static final int REQUEST_BUFFER_SIZE = OP_CODE_SIZE + 256;

  Pointer pointer;

  public Request(byte opCode) {
    pointer = new Memory(REQUEST_BUFFER_SIZE);
    pointer.setByte(0, opCode);
  }

  public byte getOpCode() {
    return pointer.getByte(0);
  }

  public Pointer getDataPointer() {
    return pointer.share(1);
  }
}
