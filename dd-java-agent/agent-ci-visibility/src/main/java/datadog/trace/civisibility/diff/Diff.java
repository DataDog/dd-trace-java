package datadog.trace.civisibility.diff;

import datadog.trace.civisibility.ipc.serialization.PolymorphicSerializer;
import datadog.trace.civisibility.ipc.serialization.SerializableType;

public interface Diff extends SerializableType {

  PolymorphicSerializer<Diff> SERIALIZER = new PolymorphicSerializer<>(LineDiff.class);

  boolean contains(String relativePath, int startLine, int endLine);
}
