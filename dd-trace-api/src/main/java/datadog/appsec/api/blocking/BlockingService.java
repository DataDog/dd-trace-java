package datadog.appsec.api.blocking;

import java.util.Map;
import javax.annotation.Nonnull;

public interface BlockingService {
  BlockingService NOOP = new BlockingServiceNoop();

  BlockingDetails shouldBlockUser(@Nonnull String userId);

  boolean tryCommitBlockingResponse(
      int statusCode,
      String blockId,
      @Nonnull BlockingContentType type,
      @Nonnull Map<String, String> extraHeaders);

  class BlockingServiceNoop implements BlockingService {
    private BlockingServiceNoop() {}

    @Override
    public BlockingDetails shouldBlockUser(@Nonnull String userId) {
      return null;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        int statusCode,
        String blockId,
        @Nonnull BlockingContentType type,
        @Nonnull Map<String, String> extraHeaders) {
      return false;
    }
  }
}
