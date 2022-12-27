package datadog.trace.bootstrap.instrumentation.usmextractor;

import sun.security.ssl.SSLSocketImpl;
import java.util.function.Function;
import com.sun.jna.Library;
import com.sun.jna.NativeLong;
import com.sun.jna.Native;
import com.sun.jna.Pointer;


public class UsmExtractor {

  static final NativeLong REQUEST_CODE = new NativeLong(0xda7ad09);
  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong ioctl(NativeLong fd, NativeLong request, Object... args);
  }

  public static void send(UsmMessage message) {
    System.out.println("inside foo: socket");
    if (!message.validate()){
      System.out.println("invalid message");
    }
    NativeLong res = CLibrary.Instance.ioctl(new NativeLong(0), UsmMessage.USM_IOCTL_ID, Pointer.nativeValue(message.getBufferPtr()));
    System.out.println("ioctl result: " + res.toString());
  }

}
