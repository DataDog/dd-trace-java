package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;

public class RepoIndexRequest implements Signal {
  public static final Signal INSTANCE = new RepoIndexRequest();

  @Override
  public SignalType getType() {
    return SignalType.REPO_INDEX_REQUEST;
  }

  @Override
  public ByteBuffer serialize() {
    return ByteBuffer.allocate(0);
  }
}
