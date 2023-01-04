package datadog.trace.api.iast;

import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.api.iast.propagation.UrlModule;
import datadog.trace.api.iast.sink.*;
import datadog.trace.api.iast.source.WebModule;

/** Bridge between instrumentations and {@link IastModule} instances. */
public abstract class InstrumentationBridge {

  public static volatile StringModule STRING;
  public static volatile UrlModule URL;
  public static volatile WebModule WEB;
  public static volatile SqlInjectionModule SQL_INJECTION;
  public static volatile PathTraversalModule PATH_TRAVERSAL;
  public static volatile CommandInjectionModule COMMAND_INJECTION;
  public static volatile WeakCipherModule WEAK_CIPHER;
  public static volatile WeakHashModule WEAK_HASH;
  public static volatile LdapInjectionModule LDAP_INJECTION;

  private InstrumentationBridge() {}

  public static void registerIastModule(final IastModule module) {
    if (module instanceof StringModule) {
      STRING = (StringModule) module;
    } else if (module instanceof UrlModule) {
      URL = (UrlModule) module;
    } else if (module instanceof WebModule) {
      WEB = (WebModule) module;
    } else if (module instanceof SqlInjectionModule) {
      SQL_INJECTION = (SqlInjectionModule) module;
    } else if (module instanceof PathTraversalModule) {
      PATH_TRAVERSAL = (PathTraversalModule) module;
    } else if (module instanceof CommandInjectionModule) {
      COMMAND_INJECTION = (CommandInjectionModule) module;
    } else if (module instanceof WeakCipherModule) {
      WEAK_CIPHER = (WeakCipherModule) module;
    } else if (module instanceof WeakHashModule) {
      WEAK_HASH = (WeakHashModule) module;
    } else if (module instanceof LdapInjectionModule) {
      LDAP_INJECTION = (LdapInjectionModule) module;
    } else {
      throw new UnsupportedOperationException("Module not yet supported: " + module);
    }
  }

  /** Mainly used for testing modules */
  @SuppressWarnings("unchecked")
  public static <E extends IastModule> E getIastModule(final Class<E> type) {
    if (type == StringModule.class) {
      return (E) STRING;
    }
    if (type == UrlModule.class) {
      return (E) URL;
    }
    if (type == WebModule.class) {
      return (E) WEB;
    }
    if (type == SqlInjectionModule.class) {
      return (E) SQL_INJECTION;
    }
    if (type == PathTraversalModule.class) {
      return (E) PATH_TRAVERSAL;
    }
    if (type == CommandInjectionModule.class) {
      return (E) COMMAND_INJECTION;
    }
    if (type == WeakCipherModule.class) {
      return (E) WEAK_CIPHER;
    }
    if (type == WeakHashModule.class) {
      return (E) WEAK_HASH;
    }
    if (type == LdapInjectionModule.class) {
      return (E) LDAP_INJECTION;
    }
    throw new UnsupportedOperationException("Module not yet supported: " + type);
  }

  /** Mainly used for testing empty modules */
  public static void clearIastModules() {
    STRING = null;
    URL = null;
    WEB = null;
    SQL_INJECTION = null;
    PATH_TRAVERSAL = null;
    COMMAND_INJECTION = null;
    WEAK_CIPHER = null;
    WEAK_HASH = null;
    LDAP_INJECTION = null;
  }
}
