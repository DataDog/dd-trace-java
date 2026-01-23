package datadog.trace.agent.tooling.usm;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import datadog.trace.agent.tooling.usm.UsmMessageImpl.BaseUsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UsmExtractorImpl implements UsmExtractor {
  private static final Logger log = LoggerFactory.getLogger(UsmExtractorImpl.class);

  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong ioctl(NativeLong fd, NativeLong request, Object... args);
  }

  public void send(UsmMessage message) {
    if (message.validate()) {
      BaseUsmMessage bm = (BaseUsmMessage) message;

      log.debug(" sending ioctl: " + String.format("%08x", UsmMessageImpl.USM_IOCTL_ID.intValue()));
      NativeLong res =
          CLibrary.Instance.ioctl(
              new NativeLong(0),
              UsmMessageImpl.USM_IOCTL_ID,
              Pointer.nativeValue(bm.getBufferPtr()));
      log.debug("ioctl result: {}", String.format("%08x", res.intValue()));
    } else {
      log.debug("INVALID MESSAGE: {}", message.getClass());
    }
  }

  public static void registerAsSupplier() {
    UsmExtractor.Supplier.registerIfAbsent(new UsmExtractorImpl());
  }
}
