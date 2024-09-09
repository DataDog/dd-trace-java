package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.util.BitSet;

public class ClassMatchingResponse implements SignalResponse {

  private final BitSet ids;

  public ClassMatchingResponse(BitSet ids) {
    this.ids = ids;
  }

  public BitSet getIds() {
    return ids;
  }

  @Override
  public SignalType getType() {
    return SignalType.CLASS_MATCHING_RESPONSE;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    if (ids == null) {
      s.write(-1);
    } else {
      s.write(ids.cardinality());
      for (int id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1)) {
        s.write(id);
      }
    }
    return s.flush();
  }

  public static ClassMatchingResponse deserialize(ByteBuffer buffer) {
    BitSet ids;
    int idsCount = Serializer.readInt(buffer);
    if (idsCount >= 0) {
      ids = new BitSet();
      for (int i = 0; i < idsCount; i++) {
        ids.set(Serializer.readInt(buffer));
      }
    } else {
      ids = null;
    }
    return new ClassMatchingResponse(ids);
  }
}
