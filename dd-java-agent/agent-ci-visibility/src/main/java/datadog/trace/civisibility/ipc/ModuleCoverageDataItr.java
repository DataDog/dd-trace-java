package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

public class ModuleCoverageDataItr extends ModuleSignal {

  @Nonnull private final Map<String, BitSet> coveredLinesByRelativeSourcePath;

  public ModuleCoverageDataItr(
      long sessionId,
      long moduleId,
      @Nonnull Map<String, BitSet> coveredLinesByRelativeSourcePath) {
    super(sessionId, moduleId);
    this.coveredLinesByRelativeSourcePath = coveredLinesByRelativeSourcePath;
  }

  @Nonnull
  public Map<String, BitSet> getCoveredLinesByRelativeSourcePath() {
    return coveredLinesByRelativeSourcePath;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ModuleCoverageDataItr that = (ModuleCoverageDataItr) o;
    return sessionId == that.sessionId
        && moduleId == that.moduleId
        && coveredLinesByRelativeSourcePath.equals(that.coveredLinesByRelativeSourcePath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionId, moduleId, coveredLinesByRelativeSourcePath.hashCode());
  }

  @Override
  public String toString() {
    return "ModuleCoverageDataItr{" + "sessionId=" + sessionId + ", moduleId=" + moduleId + '}';
  }

  @Override
  public SignalType getType() {
    return SignalType.MODULE_COVERAGE_DATA_ITR;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(sessionId);
    s.write(moduleId);
    s.write(
        coveredLinesByRelativeSourcePath,
        Serializer::write,
        (sr, bs) -> sr.write(bs.toByteArray()));
    return s.flush();
  }

  public static ModuleCoverageDataItr deserialize(ByteBuffer buffer) {
    long sessionId = Serializer.readLong(buffer);
    long moduleId = Serializer.readLong(buffer);
    Map<String, BitSet> coveredLinesByRelativeSourcePath =
        Serializer.readMap(
            buffer,
            Serializer::readString,
            b -> {
              byte[] bytes = Serializer.readByteArray(b);
              return bytes != null ? BitSet.valueOf(bytes) : null;
            });
    return new ModuleCoverageDataItr(sessionId, moduleId, coveredLinesByRelativeSourcePath);
  }
}
