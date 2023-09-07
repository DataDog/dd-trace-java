package datadog.trace.api.iast;

import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import datadog.trace.api.iast.sink.HstsMissingHeaderModule;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule;
import datadog.trace.api.iast.sink.NoSameSiteCookieModule;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.api.iast.sink.WeakCipherModule;
import datadog.trace.api.iast.sink.WeakHashModule;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import datadog.trace.api.iast.sink.XContentTypeModule;
import datadog.trace.api.iast.sink.XPathInjectionModule;
import datadog.trace.api.iast.sink.XssModule;
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
  public static volatile InsecureCookieModule<?> INSECURE_COOKIE;
  public static volatile NoHttpOnlyCookieModule<?> NO_HTTPONLY_COOKIE;
  public static volatile NoSameSiteCookieModule<?> NO_SAMESITE_COOKIE;
  public static volatile SsrfModule SSRF;
  public static volatile UnvalidatedRedirectModule UNVALIDATED_REDIRECT;
  public static volatile WeakRandomnessModule WEAK_RANDOMNESS;
  public static volatile HttpResponseHeaderModule RESPONSE_HEADER_MODULE;
  public static volatile HstsMissingHeaderModule HSTS_MISSING_HEADER_MODULE;

  public static volatile XContentTypeModule X_CONTENT_TYPE_HEADER_MODULE;

  public static volatile TrustBoundaryViolationModule TRUST_BOUNDARY_VIOLATION;

  public static volatile XPathInjectionModule XPATH_INJECTION;

  public static volatile XssModule XSS;

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
      INSECURE_COOKIE = (InsecureCookieModule<?>) module;
    } else if (module instanceof NoHttpOnlyCookieModule) {
      NO_HTTPONLY_COOKIE = (NoHttpOnlyCookieModule<?>) module;
    } else if (module instanceof NoSameSiteCookieModule) {
      NO_SAMESITE_COOKIE = (NoSameSiteCookieModule<?>) module;
    } else if (module instanceof SsrfModule) {
      SSRF = (SsrfModule) module;
    } else if (module instanceof UnvalidatedRedirectModule) {
      UNVALIDATED_REDIRECT = (UnvalidatedRedirectModule) module;
    } else if (module instanceof WeakRandomnessModule) {
      WEAK_RANDOMNESS = (WeakRandomnessModule) module;
    } else if (module instanceof HttpResponseHeaderModule) {
      RESPONSE_HEADER_MODULE = (HttpResponseHeaderModule) module;
    } else if (module instanceof HstsMissingHeaderModule) {
      HSTS_MISSING_HEADER_MODULE = (HstsMissingHeaderModule) module;
    } else if (module instanceof XContentTypeModule) {
      X_CONTENT_TYPE_HEADER_MODULE = (XContentTypeModule) module;
    } else if (module instanceof XPathInjectionModule) {
      XPATH_INJECTION = (XPathInjectionModule) module;
    } else if (module instanceof TrustBoundaryViolationModule) {
      TRUST_BOUNDARY_VIOLATION = (TrustBoundaryViolationModule) module;
    } else if (module instanceof XssModule) {
      XSS = (XssModule) module;
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
    if (type == NoHttpOnlyCookieModule.class) {
      return (E) NO_HTTPONLY_COOKIE;
    }
    if (type == NoSameSiteCookieModule.class) {
      return (E) NO_SAMESITE_COOKIE;
    }
    if (type == SsrfModule.class) {
      return (E) SSRF;
    }
    if (type == UnvalidatedRedirectModule.class) {
      return (E) UNVALIDATED_REDIRECT;
    }
    if (type == WeakRandomnessModule.class) {
      return (E) WEAK_RANDOMNESS;
    }
    if (type == XPathInjectionModule.class) {
      return (E) XPATH_INJECTION;
    }
    if (type == HttpResponseHeaderModule.class) {
      return (E) RESPONSE_HEADER_MODULE;
    }
    if (type == HstsMissingHeaderModule.class) {
      return (E) HSTS_MISSING_HEADER_MODULE;
    }
    if (type == XContentTypeModule.class) {
      return (E) X_CONTENT_TYPE_HEADER_MODULE;
    }
    if (type == TrustBoundaryViolationModule.class) {
      return (E) TRUST_BOUNDARY_VIOLATION;
    }
    if (type == XssModule.class) {
      return (E) XSS;
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
    NO_HTTPONLY_COOKIE = null;
    NO_SAMESITE_COOKIE = null;
    SSRF = null;
    UNVALIDATED_REDIRECT = null;
    WEAK_RANDOMNESS = null;
    RESPONSE_HEADER_MODULE = null;
    HSTS_MISSING_HEADER_MODULE = null;
    X_CONTENT_TYPE_HEADER_MODULE = null;
    XPATH_INJECTION = null;
    TRUST_BOUNDARY_VIOLATION = null;
    XSS = null;
  }
}
