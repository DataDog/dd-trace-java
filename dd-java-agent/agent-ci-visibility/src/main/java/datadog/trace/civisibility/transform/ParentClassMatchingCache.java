package datadog.trace.civisibility.transform;

import datadog.trace.api.civisibility.ClassMatchingCache;
import datadog.trace.civisibility.domain.buildsystem.ProxyTestModule;
import datadog.trace.civisibility.ipc.ClassMatchingRecord;
import datadog.trace.civisibility.ipc.ClassMatchingResponse;
import datadog.trace.civisibility.ipc.SignalClient;
import java.net.URL;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// FIXME nikita: rename?
// FIXME nikita: is it a problem that this happens _before_ the agent instrumentation? Some classes
// are loaded
public class ParentClassMatchingCache implements ClassMatchingCache {

  private static final Logger log = LoggerFactory.getLogger(ProxyTestModule.class);

  private final SignalClient.Factory signalClientFactory;
  private final Collection<ClassMatchingRecord.ClassMatchingResult> matchingResults =
      new ConcurrentLinkedQueue<>();
  private final Map<MatchKey, BitSet> cachedResults;

  public ParentClassMatchingCache(SignalClient.Factory signalClientFactory) {
    this.signalClientFactory = signalClientFactory;

    this.cachedResults = new HashMap<>();
    try (SignalClient signalClient = signalClientFactory.create()) {
      ClassMatchingResponse response = signalClient.send(ClassMatchingRequest.INSTANCE);
      for (ClassMatchingRecord.ClassMatchingResult result : response.getResults()) {
        cachedResults.put(new MatchKey(result.getName(), result.getClassFile()), result.getIds());
      }
    } catch (Exception e) {
      // FIXME nikita: log
    }
  }

  @Override
  public void recordMatchingResult(
      String name,
      URL classFile,
      BitSet ids) { // FIXME nikita: does it work with JDK core classes? Could I have the same
    // classFile and name for different JDKs?
    try {
      matchingResults.add(
          new ClassMatchingRecord.ClassMatchingResult(
              name, classFile, (BitSet) ids.clone())); // FIXME nikita: something instead of cloning
    } catch (Exception e) {
      log.debug("Could not record class matching result: {}, {}", name, classFile, e);
    }
  }

  @Override
  public BitSet getRecordedMatchingResult(String name, URL classFile) {
    return cachedResults.get(new MatchKey(name, classFile));
  }

  public void shutdown() {
    try (SignalClient signalClient = signalClientFactory.create()) {
      signalClient.send(new ClassMatchingRecord(matchingResults));
      log.info("Matching results sent"); // FIXME nikita: remove
    } catch (Exception e) {
      log.debug("Error while sending class matching results", e);
    }
  }

  private static final class MatchKey {
    private final String name;
    private final URL classFile;

    private MatchKey(String name, URL classFile) {
      this.name = name;
      this.classFile = classFile;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MatchKey matchKey = (MatchKey) o;
      return Objects.equals(name, matchKey.name) && Objects.equals(classFile, matchKey.classFile);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, classFile);
    }
  }
}
