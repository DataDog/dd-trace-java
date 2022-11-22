package datadog.trace.instrumentation.javax.naming.directory;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.naming.Name;
import javax.naming.directory.SearchControls;

@CallSite(spi = IastAdvice.class)
public class InitialDirContextCallSite {

  @CallSite.Before(
      "javax.naming.NamingEnumeration javax.naming.directory.InitialDirContext.search(java.lang.String, java.lang.String, javax.naming.directory.SearchControls)")
  public static void beforeSearch(
      @CallSite.Argument @Nullable final String name,
      @CallSite.Argument @Nonnull final String filter,
      @CallSite.Argument @Nullable final SearchControls cons) {
    InstrumentationBridge.onDirContextSearch(name, filter, null);
  }

  @CallSite.Before(
      "javax.naming.NamingEnumeration javax.naming.directory.InitialDirContext.search(java.lang.String, java.lang.String, java.lang.Object[], javax.naming.directory.SearchControls)")
  public static void beforeSearch(
      @CallSite.Argument @Nullable final String name,
      @CallSite.Argument @Nonnull final String filterExpr,
      @CallSite.Argument @Nullable Object[] filterArgs,
      @CallSite.Argument @Nullable final SearchControls cons) {
    InstrumentationBridge.onDirContextSearch(name, filterExpr, filterArgs);
  }

  @CallSite.Before(
      "javax.naming.NamingEnumeration javax.naming.directory.InitialDirContext.search(javax.naming.Name, java.lang.String, javax.naming.directory.SearchControls)")
  public static void beforeSearch(
      @CallSite.Argument @Nullable final Name name,
      @CallSite.Argument @Nonnull final String filter,
      @CallSite.Argument @Nullable final SearchControls cons) {
    InstrumentationBridge.onDirContextSearch(null, filter, null);
  }

  @CallSite.Before(
      "javax.naming.NamingEnumeration javax.naming.directory.InitialDirContext.search(javax.naming.Name, java.lang.String, java.lang.Object[], javax.naming.directory.SearchControls)")
  public static void beforeSearch(
      @CallSite.Argument @Nullable final Name name,
      @CallSite.Argument @Nonnull final String filterExpr,
      @CallSite.Argument @Nullable Object[] filterArgs,
      @CallSite.Argument @Nullable final SearchControls cons) {
    InstrumentationBridge.onDirContextSearch(null, filterExpr, filterArgs);
  }
}
