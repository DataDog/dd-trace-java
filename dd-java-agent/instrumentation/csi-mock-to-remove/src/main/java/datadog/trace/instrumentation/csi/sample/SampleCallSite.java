package datadog.trace.instrumentation.csi.sample;

import datadog.trace.agent.tooling.csi.CallSite;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.bytebuddy.asm.Advice;

@CallSite(
    spi = SampleCallSite.SampleCallSiteSpi.class,
    helpers = {SampleCallSite.Handler.class, SampleCallSite.DefaultHandler.class})
public class SampleCallSite {

  public static Handler HANDLER;

  @CallSite.Around(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static MessageDigest aroundMessageDigestGetInstance(
      @Advice.Argument(0) final String algorithm) throws NoSuchAlgorithmException {
    if (HANDLER != null) {
      return HANDLER.aroundMessageDigestGetInstance(algorithm);
    }
    return MessageDigest.getInstance(algorithm);
  }

  @CallSite.Before("java.lang.String java.lang.String.concat(java.lang.String)")
  public static void beforeStringConcat(
      @Advice.This final String self, @Advice.Argument(0) final String value) {
    if (HANDLER != null) {
      HANDLER.beforeStringConcat(self, value);
    }
  }

  @CallSite.After("void java.net.URL.<init>(java.lang.String)")
  public static URL afterURLInit(@Advice.This URL result, @Advice.Argument(0) final String value) {
    if (HANDLER != null) {
      return HANDLER.afterURLInit(value, result);
    }
    return result;
  }

  public interface SampleCallSiteSpi {}

  public interface Handler {

    URL afterURLInit(String value, URL result);

    void beforeStringConcat(String self, String value);

    MessageDigest aroundMessageDigestGetInstance(String algorithm) throws NoSuchAlgorithmException;
  }

  public static class DefaultHandler implements Handler {
    @Override
    public URL afterURLInit(final String value, final URL result) {
      System.out.printf("New URL constructed from url '%s'%n", value);
      return result;
    }

    @Override
    public void beforeStringConcat(final String self, final String value) {
      System.out.printf("String concat of '%s' with '%s'%n", self, value);
    }

    @Override
    public MessageDigest aroundMessageDigestGetInstance(final String algorithm)
        throws NoSuchAlgorithmException {
      System.out.printf("Message digest constructed with algorithm '%s'%n", algorithm);
      return MessageDigest.getInstance(algorithm);
    }
  }

  static {
    HANDLER = new DefaultHandler();
  }
}
