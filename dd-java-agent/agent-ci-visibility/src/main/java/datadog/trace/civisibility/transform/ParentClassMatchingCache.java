package datadog.trace.civisibility.transform;

import datadog.trace.api.civisibility.ClassMatchingCache;
import datadog.trace.civisibility.domain.buildsystem.ProxyTestModule;
import datadog.trace.civisibility.ipc.ClassMatchingRecord;
import datadog.trace.civisibility.ipc.ClassMatchingResponse;
import datadog.trace.civisibility.ipc.SignalClient;
import java.io.IOException;
import java.net.URL;
import java.util.BitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// FIXME nikita: rename?
public class ParentClassMatchingCache implements ClassMatchingCache {

  private static final Logger log = LoggerFactory.getLogger(ProxyTestModule.class);

  private final SignalClient signalClient;

  public ParentClassMatchingCache(SignalClient.Factory signalClientFactory) {
    signalClient = signalClientFactory.create();
  }

  @Override
  public void recordMatchingResult(String name, URL classFile, BitSet ids) {
    try {
      signalClient.send(new ClassMatchingRecord(name, classFile, ids));
    } catch (IOException e) {
      log.debug("Could not record class matching result: {}, {}", name, classFile, e);
    }
  }

  @Override
  public BitSet getRecordedMatchingResult(String name, URL classFile) {
    try {
      ClassMatchingResponse response = signalClient.send(new ClassMatchingRequest(name, classFile));
      return response.getIds();

    } catch (IOException e) {
      log.debug("Could not record class matching result: {}, {}", name, classFile, e);
      return null;
    }
  }

  // FIXME nikita: add a method to close - or a signal client pooling factory, in which case I can
  // "create" (borrow) new client every time
}
