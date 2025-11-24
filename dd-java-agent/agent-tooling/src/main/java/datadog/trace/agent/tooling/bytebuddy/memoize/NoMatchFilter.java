package datadog.trace.agent.tooling.bytebuddy.memoize;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.instrument.utils.ClassNameFilter;
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

/** Builds a persistable compact filter that records uninteresting types. */
final class NoMatchFilter {
  private static final Logger log = LoggerFactory.getLogger(NoMatchFilter.class);

  private static final String TRACER_VERSION_HEADER = "dd-java-agent";
  private static final String NO_MATCH_FILTER_HEADER = "NoMatchFilter";

  private NoMatchFilter() {}

  public static ClassNameFilter build() {
    // support persisting/restoring no-match results?
    Path noMatchFile = discoverNoMatchFile();
    if (null != noMatchFile) {
      if (Files.exists(noMatchFile)) {
        // restore existing filter with previously collected results
        return seedNoMatchFilter(noMatchFile);
      } else {
        // populate filter from current run and persist at shutdown
        ClassNameFilter filter = emptyNoMatchFilter();
        Runtime.getRuntime().addShutdownHook(new ShutdownHook(noMatchFile, filter));
        return filter;
      }
    } else {
      return emptyNoMatchFilter();
    }
  }

  private static Path discoverNoMatchFile() {
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

  private static ClassNameFilter emptyNoMatchFilter() {
    return new ClassNameFilter(InstrumenterConfig.get().getResolverNoMatchesSize());
  }

  private static ClassNameFilter seedNoMatchFilter(Path noMatchFile) {
    log.debug("Seeding NoMatchFilter from {}", noMatchFile);
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(noMatchFile)))) {
      while (true) {
        switch (in.readUTF()) {
          case TRACER_VERSION_HEADER:
            expectVersion(in, DDTraceApiInfo.VERSION);
            break;
          case NO_MATCH_FILTER_HEADER:
            return ClassNameFilter.readFrom(in);
          default:
            throw new IOException("unexpected content");
        }
      }
    } catch (Throwable e) {
      if (log.isDebugEnabled()) {
        log.info("Unable to seed NoMatchFilter from {}", noMatchFile, e);
      } else {
        log.info("Unable to seed NoMatchFilter from {}: {}", noMatchFile, e.getMessage());
      }
      return emptyNoMatchFilter();
    }
  }

  private static void expectVersion(DataInputStream in, String version) throws IOException {
    if (!version.equals(in.readUTF())) {
      throw new IOException("version mismatch");
    }
  }

  static void persistNoMatchFilter(Path noMatchFile, ClassNameFilter filter) {
    log.debug("Persisting NoMatchFilter to {}", noMatchFile);
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(noMatchFile)))) {
      out.writeUTF(TRACER_VERSION_HEADER);
      out.writeUTF(DDTraceApiInfo.VERSION);
      out.writeUTF(NO_MATCH_FILTER_HEADER);
      filter.writeTo(out);
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.info("Unable to persist NoMatchFilter to {}", noMatchFile, e);
      } else {
        log.info("Unable to persist NoMatchFilter to {}: {}", noMatchFile, e.getMessage());
      }
    }
  }

  private static final class ShutdownHook extends Thread {
    private final Path noMatchFile;
    private final ClassNameFilter filter;

    ShutdownHook(Path noMatchFile, ClassNameFilter filter) {
      super(AGENT_THREAD_GROUP, "dd-NoMatchFilter-persist-hook");
      this.noMatchFile = noMatchFile;
      this.filter = filter;
    }

    @Override
    public void run() {
      persistNoMatchFilter(noMatchFile, filter);
    }
  }
}
