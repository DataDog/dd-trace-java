package datadog.trace.api.iast;

import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.api.iast.sink.WeakCipherModule;
import datadog.trace.api.iast.sink.WeakHashModule;
import datadog.trace.api.iast.source.WebModule;

/** Bridge between instrumentations and {@link IastModule} instances. */
public abstract class InstrumentationBridge {

  public static volatile StringModule STRING;
  public static volatile CodecModule CODEC;
  public static volatile WebModule WEB;
  public static volatile SqlInjectionModule SQL_INJECTION;
  public static volatile PathTraversalModule PATH_TRAVERSAL;
  public static volatile CommandInjectionModule COMMAND_INJECTION;
  public static volatile WeakCipherModule WEAK_CIPHER;
  public static volatile WeakHashModule WEAK_HASH;
  public static volatile LdapInjectionModule LDAP_INJECTION;
  public static volatile PropagationModule PROPAGATION;
  public static volatile InsecureCookieModule INSECURE_COOKIE;
  public static volatile SsrfModule SSRF;

  private InstrumentationBridge() {}

  public static void registerIastModule(final IastModule module) {
    if (module instanceof StringModule) {
      STRING = (StringModule) module;
    } else if (module instanceof CodecModule) {
      CODEC = (CodecModule) module;
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
    } else if (module instanceof PropagationModule) {
      PROPAGATION = (PropagationModule) module;
    } else if (module instanceof InsecureCookieModule) {
      INSECURE_COOKIE = (InsecureCookieModule) module;
    } else if (module instanceof SsrfModule) {
      SSRF = (SsrfModule) module;
    } else {
      throw new UnsupportedOperationException("Module not yet supported: " + module);
    }
  }

  /** Mainly used for testing modules */
  public static <E extends IastModule> E getIastModule(final Class<E> type) {
    if (type == StringModule.class) {
      return (E) STRING;
    }
    if (type == CodecModule.class) {
      return (E) CODEC;
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
    if (type == PropagationModule.class) {
      return (E) PROPAGATION;
    }
    if (type == InsecureCookieModule.class) {
      return (E) INSECURE_COOKIE;
    }
    if (type == SsrfModule.class) {
      return (E) SSRF;
    }
    throw new UnsupportedOperationException("Module not yet supported: " + type);
  }

  /** Mainly used for testing empty modules */
  public static void clearIastModules() {
    STRING = null;
    CODEC = null;
    WEB = null;
    SQL_INJECTION = null;
    PATH_TRAVERSAL = null;
    COMMAND_INJECTION = null;
    WEAK_CIPHER = null;
    WEAK_HASH = null;
    LDAP_INJECTION = null;
    PROPAGATION = null;
    INSECURE_COOKIE = null;
  }
}
