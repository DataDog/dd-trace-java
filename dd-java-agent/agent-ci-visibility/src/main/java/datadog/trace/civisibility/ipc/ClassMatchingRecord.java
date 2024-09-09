package datadog.trace.civisibility.ipc;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.BitSet;

public class ClassMatchingRecord implements Signal {

  private final String name;
  private final URL classFile;
  private final BitSet ids;

  public ClassMatchingRecord(String name, URL classFile, BitSet ids) {
    this.name = name;
    this.classFile = classFile;
    this.ids = ids;
  }

  public String getName() {
    return name;
  }

  public URL getClassFile() {
    return classFile;
  }

  public BitSet getIds() {
    return ids;
  }

  @Override
  public SignalType getType() {
    return SignalType.CLASS_MATCHING_RECORD;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(name);
    s.write(classFile.toString());
    s.write(ids.cardinality());
    for (int id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1)) {
      s.write(id);
    }
    return s.flush();
  }

  public static ClassMatchingRecord deserialize(ByteBuffer buffer) {
    String name = Serializer.readString(buffer);
    URL classFile;
    try {
      classFile = new URL(Serializer.readString(buffer));
    } catch (MalformedURLException e) {
      throw new RuntimeException("Could not deserialize class matching resulg", e);
    }
    BitSet ids = new BitSet();
    int idsCount = Serializer.readInt(buffer);
    for (int i = 0; i < idsCount; i++) {
      ids.set(Serializer.readInt(buffer));
    }
    return new ClassMatchingRecord(name, classFile, ids);
  }
}
