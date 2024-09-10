package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;

public class ClassMatchingResponse implements SignalResponse {

  private final Collection<ClassMatchingRecord.ClassMatchingResult> results;

  public ClassMatchingResponse(Collection<ClassMatchingRecord.ClassMatchingResult> results) {
    this.results = results;
  }

  public Collection<ClassMatchingRecord.ClassMatchingResult> getResults() {
    return results;
  }

  @Override
  public SignalType getType() {
    return SignalType.CLASS_MATCHING_RESPONSE;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(results, ClassMatchingRecord.ClassMatchingResult::serialize);
    return s.flush();
  }

  public static ClassMatchingResponse deserialize(ByteBuffer buffer) {
    List<ClassMatchingRecord.ClassMatchingResult> results =
        Serializer.readList(buffer, ClassMatchingRecord.ClassMatchingResult::deserialize);
    return new ClassMatchingResponse(results);
  }
}
