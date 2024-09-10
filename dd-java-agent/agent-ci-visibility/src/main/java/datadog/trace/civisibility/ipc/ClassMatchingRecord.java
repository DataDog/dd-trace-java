package datadog.trace.civisibility.ipc;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

public class ClassMatchingRecord implements Signal {

  private final Collection<ClassMatchingResult> results;

  public ClassMatchingRecord(Collection<ClassMatchingResult> results) {
    this.results = results;
  }

  public Collection<ClassMatchingResult> getResults() {
    return results;
  }

  @Override
  public SignalType getType() {
    return SignalType.CLASS_MATCHING_RECORD;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(results, ClassMatchingResult::serialize);
    return s.flush();
  }

  public static ClassMatchingRecord deserialize(ByteBuffer buffer) {
    List<ClassMatchingResult> results =
        Serializer.readList(buffer, ClassMatchingResult::deserialize);
    return new ClassMatchingRecord(results);
  }

  // FIXME nikita: move to upper level and to a different package
  public static final class ClassMatchingResult {
    private final String name;
    private final URL classFile;
    private final BitSet ids;

    public ClassMatchingResult(String name, URL classFile, BitSet ids) {
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

    public static void serialize(Serializer s, ClassMatchingResult result) {
      s.write(result.name);
      s.write(result.classFile != null ? result.classFile.toString() : null);
      s.write(result.ids.cardinality());
      for (int id = result.ids.nextSetBit(0); id >= 0; id = result.ids.nextSetBit(id + 1)) {
        s.write(id);
      }
    }

    public static ClassMatchingResult deserialize(ByteBuffer buffer) {
      String name = Serializer.readString(buffer);
      URL classFile;
      try {
        String classFileString = Serializer.readString(buffer);
        classFile = classFileString != null ? new URL(classFileString) : null;
      } catch (MalformedURLException e) {
        throw new RuntimeException("Could not deserialize class matching result", e);
      }
      BitSet ids = new BitSet();
      int idsCount = Serializer.readInt(buffer);
      for (int i = 0; i < idsCount; i++) {
        ids.set(Serializer.readInt(buffer));
      }
      return new ClassMatchingResult(name, classFile, ids);
    }
  }
}
