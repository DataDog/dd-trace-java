package datadog.trace.core.util;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ServiceNameHashing {

  public interface HashProvider {
    String hash(String value);
  }

  public static HashProvider getHashProvider(boolean isServicePropagationEnabled) {
    return isServicePropagationEnabled ? getCachedHashProvider() : ServiceNameHashing.NO_OP;
  }

  private static final Logger log = LoggerFactory.getLogger(ServiceNameHashing.class);

  private static final class CachedHashProviderHolder {
    // TODO what would be a reasonable size for the cache?
    private static final HashProvider INSTANCE = createCachedProvider(256);
  }

  private static HashProvider getCachedHashProvider() {
    return CachedHashProviderHolder.INSTANCE;
  }

  private static final HashProvider NO_OP = new NoOpHashProvider();

  private static HashProvider createCachedProvider(int cacheCapacity) {
    HashProvider provider = Sha256First10HashProvider.create();
    if (provider == null) {
      return NO_OP;
    }
    return new CachedHashProvider(provider, cacheCapacity);
  }

  private static final class NoOpHashProvider implements HashProvider {
    @Override
    public String hash(String serviceName) {
      return "";
    }
  }

  /** Uses the first 10 hexadecimal characters of SHA256 of service name in UTF8 */
  private static final class Sha256First10HashProvider implements HashProvider {

    private static HashProvider create() {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return new Sha256First10HashProvider(digest);
      } catch (NoSuchAlgorithmException e) {
        log.warn("Failed to obtain SHA-256 message digest", e);
      }
      return null;
    }

    private final MessageDigest digest;

    private Sha256First10HashProvider(MessageDigest digest) {
      this.digest = digest;
    }

    @Override
    public String hash(String serviceName) {
      byte[] hashBytes = digest.digest(serviceName.getBytes(StandardCharsets.UTF_8));
      int len = Math.min(5, hashBytes.length);
      StringBuilder hash = new StringBuilder(len);
      for (int i = 0; i < len; i++) {
        hash.append(String.format("%02x", 0xFF & hashBytes[i]));
      }
      return hash.toString();
    }
  }

  private static final class CachedHashProvider implements HashProvider {
    private final DDCache<String, String> cache;
    private final HashProvider provider;

    private final Function<String, String> CALS_SERVICE_NAME_HASH =
        new Function<String, String>() {
          @Override
          public String apply(String input) {
            return provider.hash(input);
          }
        };

    public CachedHashProvider(HashProvider provider, int cacheCapacity) {
      this.provider = provider;
      this.cache = DDCaches.newFixedSizeCache(cacheCapacity);
    }

    @Override
    public String hash(String serviceName) {
      return cache.computeIfAbsent(serviceName, CALS_SERVICE_NAME_HASH);
    }
  }
}
