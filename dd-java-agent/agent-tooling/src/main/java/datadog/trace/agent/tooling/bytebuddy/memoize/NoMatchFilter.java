package datadog.trace.agent.tooling.bytebuddy.memoize;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.trace.agent.tooling.bytebuddy.ClassCodeFilter;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Compact filter that records uninteresting types. */
final class NoMatchFilter extends ClassCodeFilter {
  private static final Logger log = LoggerFactory.getLogger(NoMatchFilter.class);

  NoMatchFilter() {
    super(InstrumenterConfig.get().getResolverNoMatchesSize());

    // seed filter from previously collected results?
    Path noMatchFile = discoverNoMatchFile();
    if (null != noMatchFile) {
      seedNoMatchFilter(noMatchFile);
    }
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

  void seedNoMatchFilter(Path noMatchFile) {
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

  void persistNoMatchFilter(Path noMatchFile) {
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

  class ShutdownHook extends Thread {
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
