package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.source.index.RepoIndex;
import java.nio.ByteBuffer;

public class RepoIndexResponse implements SignalResponse {

  private final RepoIndex index;

  public RepoIndexResponse(RepoIndex index) {
    this.index = index;
  }

  public RepoIndex getIndex() {
    return index;
  }

  @Override
  public SignalType getType() {
    return SignalType.REPO_INDEX_RESPONSE;
  }

  @Override
  public ByteBuffer serialize() {
    return index.serialize();
  }

  public static RepoIndexResponse deserialize(ByteBuffer buffer) {
    RepoIndex repoIndex = RepoIndex.deserialize(buffer);
    return new RepoIndexResponse(repoIndex);
  }
}
