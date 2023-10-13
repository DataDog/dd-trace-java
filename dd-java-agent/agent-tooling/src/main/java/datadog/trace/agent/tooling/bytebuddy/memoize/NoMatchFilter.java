package datadog.trace.agent.tooling.bytebuddy.memoize;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.api.InstrumenterConfig;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compact filter that records the hash and short 'class-code' for uninteresting types.
 *
 * <p>The 'class-code' includes the length of the package prefix and simple name, as well as the
 * first and last characters of the simple name. These elements coupled with the hash of the full
 * class name should minimize the probability of collisions without needing to store full names,
 * which would otherwise make the no-match filter overly large.
 */
final class NoMatchFilter {
  private static final Logger log = LoggerFactory.getLogger(NoMatchFilter.class);

  private static final int MAX_CAPACITY = 1 << 16;
  private static final int MIN_CAPACITY = 1 << 8;
  private static final int MAX_HASH_ATTEMPTS = 3;

  private static final long[] slots;
  private static final int slotMask;

  static {
    int capacity = InstrumenterConfig.get().getResolverNoMatchesSize();
    if (capacity < MIN_CAPACITY) {
      capacity = MIN_CAPACITY;
    } else if (capacity > MAX_CAPACITY) {
      capacity = MAX_CAPACITY;
    }

    // choose enough slot bits to cover the chosen capacity
    slotMask = 0xFFFFFFFF >>> Integer.numberOfLeadingZeros(capacity - 1);
    slots = new long[slotMask + 1];

    // seed filter from previously collected results?
    Path noMatchFile = discoverNoMatchFile();
    if (null != noMatchFile) {
      seedNoMatchFilter(noMatchFile);
    }
  }

  public static boolean contains(String name) {
    int hash = name.hashCode();
    for (int i = 1, h = hash; true; i++) {
      long value = slots[slotMask & h];
      if (value == 0) {
        return false;
      } else if ((int) value == hash) {
        return (int) (value >>> 32) == classCode(name);
      } else if (i == MAX_HASH_ATTEMPTS) {
        return false;
      }
      h = rehash(h);
    }
  }

  public static void add(String name) {
    int index;
    int hash = name.hashCode();
    for (int i = 1, h = hash; true; i++) {
      index = slotMask & h;
      if (slots[index] == 0) {
        break;
      } else if (i == MAX_HASH_ATTEMPTS) {
        index = slotMask & hash; // overwrite original slot
        break;
      }
      h = rehash(h);
    }
    slots[index] = (long) classCode(name) << 32 | 0xFFFFFFFFL & hash;
  }

  public static void clear() {
    Arrays.fill(slots, 0);
  }

  /**
   * Computes a 32-bit 'class-code' that includes the length of the package prefix and simple name,
   * plus the first and last characters of the simple name (each truncated to fit into 8-bits.)
   */
  private static int classCode(String name) {
    int start = name.lastIndexOf('.') + 1;
    int end = name.length() - 1;
    int code = 0xFF & start;
    code = (code << 8) | (0xFF & name.charAt(start));
    code = (code << 8) | (0xFF & name.charAt(end));
    return (code << 8) | (0xFF & (end - start));
  }

  private static int rehash(int oldHash) {
    return Integer.reverseBytes(oldHash * 0x9e3775cd) * 0x9e3775cd;
  }

  static Path discoverNoMatchFile() {
    String cacheDir = InstrumenterConfig.get().getResolverCacheDir();
    if (null == cacheDir) {
      return null;
    }

    // use different file for each tracer + service combination
    String filterKey =
        DDTraceApiInfo.VERSION
            + "/"
            + Config.get().getServiceName()
            + "/"
            + Config.get().getVersion();

    String noMatchFilterName =
        UUID.nameUUIDFromBytes(filterKey.getBytes(StandardCharsets.UTF_8)) + "-nomatch.filter";

    return Paths.get(cacheDir, noMatchFilterName);
  }

  static void seedNoMatchFilter(Path noMatchFile) {
    if (!Files.exists(noMatchFile)) {
      Runtime.getRuntime().addShutdownHook(new ShutdownHook(noMatchFile));
    } else {
      log.debug("Seeding NoMatchFilter from {}", noMatchFile);
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(Files.newInputStream(noMatchFile)))) {
        while (true) {
          switch (in.readUTF()) {
            case "dd-java-agent":
              expectVersion(in, DDTraceApiInfo.VERSION);
              break;
            case "NoMatchFilter":
              if (in.readInt() != slots.length) {
                throw new IOException("filter size mismatch");
              }
              for (int i = 0; i < slots.length; i++) {
                slots[i] = in.readLong();
              }
              return;
            default:
              throw new IOException("unexpected content");
          }
        }
      } catch (IOException e) {
        if (log.isDebugEnabled()) {
          log.info("Unable to seed NoMatchFilter from {}", noMatchFile, e);
        } else {
          log.info("Unable to seed NoMatchFilter from {}: {}", noMatchFile, e.getMessage());
        }
      }
    }
  }

  static void persistNoMatchFilter(Path noMatchFile) {
    log.debug("Persisting NoMatchFilter to {}", noMatchFile);
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(noMatchFile)))) {
      out.writeUTF("dd-java-agent");
      out.writeUTF(DDTraceApiInfo.VERSION);
      out.writeUTF("NoMatchFilter");
      out.writeInt(slots.length);
      for (long slot : slots) {
        out.writeLong(slot);
      }
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.info("Unable to persist NoMatchFilter to {}", noMatchFile, e);
      } else {
        log.info("Unable to persist NoMatchFilter to {}: {}", noMatchFile, e.getMessage());
      }
    }
  }

  static void expectVersion(DataInputStream in, String version) throws IOException {
    if (!version.equals(in.readUTF())) {
      throw new IOException("version mismatch");
    }
  }

  static class ShutdownHook extends Thread {
    private final Path noMatchFile;

    ShutdownHook(Path noMatchFile) {
      super(AGENT_THREAD_GROUP, "dd-NoMatchFilter-persist-hook");
      this.noMatchFile = noMatchFile;
    }

    @Override
    public void run() {
      persistNoMatchFilter(noMatchFile);
    }
  }
}
