package datadog.trace.instrumentation.httpclient;

import datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver;
import datadog.trace.bootstrap.instrumentation.java.net.JpmsInetAddressHelper;
import java.net.InetAddress;
import net.bytebuddy.asm.Advice;

public class JpmsInetAddressClearanceAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void openOnReturn() {
    if (JpmsInetAddressHelper.OPENED.compareAndSet(false, true)) {
      // This call needs imperatively to be done from the same module we're adding opens to,
      // because the JDK checks that the caller belongs to the same module.
      // The code of this advice is inlined into the constructor of InetAddress (java.base),
      // so it will work. Moving the same call to a helper class won't.
      InetAddress.class.getModule().addOpens("java.net", HostNameResolver.class.getModule());
      // Now that java.net is open for deep reflection, initialize the HostNameResolver handles
      HostNameResolver.tryInitialize();
    }
  }
}
