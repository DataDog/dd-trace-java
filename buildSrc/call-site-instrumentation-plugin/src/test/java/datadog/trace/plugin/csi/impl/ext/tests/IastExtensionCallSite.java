package datadog.trace.plugin.csi.impl.ext.tests;

import datadog.trace.agent.tooling.csi.CallSite;
import java.io.BufferedReader;
import java.util.Random;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.naming.Name;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

@CallSite(spi = IastCallSites.class)
public class IastExtensionCallSite {

  @Source(SourceTypes.REQUEST_HEADER_NAME_STRING)
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getHeader(java.lang.String)")
  public static String afterGetHeader(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String headerName,
      @CallSite.Return final String headerValue) {
    return headerValue;
  }

  @Propagation
  @CallSite.After("java.io.BufferedReader javax.servlet.ServletRequest.getReader()")
  public static BufferedReader afterGetReader(
      @CallSite.This final ServletRequest self,
      @CallSite.Return final BufferedReader bufferedReader) {
    return bufferedReader;
  }

  @Propagation
  @CallSite.Before("void java.lang.String.<init>(byte[])")
  public static void beforeByteArrayCtor(@CallSite.AllArguments final Object[] args) {}

  @Propagation
  @CallSite.After("byte[] java.lang.String.getBytes()")
  public static byte[] afterGetBytes(
      @CallSite.This final String target, @CallSite.Return final byte[] result) {
    return result;
  }

  @Sink("Test")
  @CallSite.Before(
      "javax.naming.NamingEnumeration javax.naming.directory.DirContext.search(javax.naming.Name, java.lang.String, java.lang.Object[], javax.naming.directory.SearchControls)")
  public static void beforeSearch(
      @Nullable @CallSite.Argument Name name,
      @Nonnull @CallSite.Argument String filterExpr,
      @Nullable @CallSite.Argument Object[] filterArgs,
      @Nullable @CallSite.Argument SearchControls cons) {}

  @Sink("Test")
  @CallSite.Before(
      "javax.naming.NamingEnumeration javax.naming.directory.DirContext.search(javax.naming.Name, java.lang.String, java.lang.Object[], javax.naming.directory.SearchControls)")
  public static void beforeSearchAllArgs(
      @Nullable @CallSite.This DirContext self, @Nullable @CallSite.AllArguments Object[] args) {}

  @Sink("Test")
  @CallSite.Around("boolean java.util.Random.nextBoolean()")
  public static boolean aroundNextBoolean(@CallSite.This final Random random) {
    return random.nextBoolean();
  }
}
