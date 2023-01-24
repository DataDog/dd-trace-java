package datadog.trace.agent.tooling;

import sun.security.ssl.SSLSocketImpl;
import com.sun.jna.Library;
import com.sun.jna.NativeLong;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import datadog.trace.agent.tooling.UsmMessageImpl.BaseUsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;

//TODO: maybe move to a separate module: "agent-usm" (similar to agent-iast, agent-debugger, etc... under the dd-java-agent)
public class UsmExtractorImpl implements UsmExtractor {
  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong ioctl(NativeLong fd, NativeLong request, Object... args);
  }

  public void send(UsmMessage message) {
    if (message.validate()) {
      BaseUsmMessage bm = (BaseUsmMessage) message;

      System.out.println(" sending ioctl: " + String.format("%08x", UsmMessageImpl.USM_IOCTL_ID.intValue()));
      NativeLong res = CLibrary.Instance.ioctl(new NativeLong(0), UsmMessageImpl.USM_IOCTL_ID,
          Pointer.nativeValue(bm.getBufferPtr()));
      System.out.println("ioctl result: " + String.format("%08x", res.intValue()));
    } else {
      System.out.println("INVALID MESSAGE: " + message.getClass().toString());
    }
  }

  public static void registerAsSupplier() {
    UsmExtractor.Supplier.registerIfAbsent(
        new UsmExtractorImpl());
  }
}
