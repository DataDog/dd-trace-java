package datadog.trace.civisibility.transform;

import datadog.trace.civisibility.ipc.Serializer;
import datadog.trace.civisibility.ipc.Signal;
import datadog.trace.civisibility.ipc.SignalType;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;

public class ClassMatchingRequest implements Signal {

  private final String name;
  private final URL classFile;

  public ClassMatchingRequest(String name, URL classFile) {
    this.name = name;
    this.classFile = classFile;
  }

  public String getName() {
    return name;
  }

  public URL getClassFile() {
    return classFile;
  }

  @Override
  public SignalType getType() {
    return SignalType.CLASS_MATCHING_REQUEST;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(name);
    s.write(classFile.toString());
    return s.flush();
  }

  public static ClassMatchingRequest deserialize(ByteBuffer buffer) {
    String name = Serializer.readString(buffer);
    URL classFile;
    try {
      classFile = new URL(Serializer.readString(buffer));
    } catch (MalformedURLException e) {
      throw new RuntimeException("Could not deserialize class matching resulg", e);
    }
    return new ClassMatchingRequest(name, classFile);
  }
}
