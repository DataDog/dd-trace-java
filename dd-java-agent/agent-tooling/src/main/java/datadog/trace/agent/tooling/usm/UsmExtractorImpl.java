package datadog.trace.agent.tooling.usm;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import datadog.trace.bootstrap.instrumentation.usm.Extractor;
import java.nio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UsmExtractorImpl implements Extractor {
  private static final Logger log = LoggerFactory.getLogger(UsmExtractorImpl.class);

  private static final NativeLong USM_IOCTL_ID = new NativeLong(0xda7ad09L);;

  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong ioctl(NativeLong fd, NativeLong request, Object... args);
  }

  public void send(Buffer buffer) {
    if (!buffer.isDirect()) {
      log.error("message buffer is not direct");
      return;
    }

    log.debug("sending ioctl: {} with buffer of size: {}",
            String.format("%08x", USM_IOCTL_ID.intValue()),
            buffer.position());
    NativeLong res =
        CLibrary.Instance.ioctl(
            new NativeLong(0),
            USM_IOCTL_ID,
            Pointer.nativeValue(Native.getDirectBufferPointer(buffer)));
    log.debug("ioctl result: {}", String.format("%08x", res.intValue()));
  }

  public static void registerAsSupplier() {
    Extractor.Supplier.registerIfAbsent(new UsmExtractorImpl());
  }
}
