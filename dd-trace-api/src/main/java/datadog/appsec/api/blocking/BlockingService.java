package datadog.appsec.api.blocking;

import javax.annotation.Nonnull;

public interface BlockingService {
  BlockingService NOOP = new BlockingServiceNoop();

  BlockingDetails shouldBlockUser(@Nonnull String userId);

  boolean tryCommitBlockingResponse(int statusCode, @Nonnull BlockingContentType type);

  class BlockingServiceNoop implements BlockingService {
    private BlockingServiceNoop() {}

    @Override
    public BlockingDetails shouldBlockUser(@Nonnull String userId) {
      return null;
    }

    @Override
    public boolean tryCommitBlockingResponse(int statusCode, @Nonnull BlockingContentType type) {
      return false;
    }
  }
}
