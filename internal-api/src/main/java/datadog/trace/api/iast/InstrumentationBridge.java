package datadog.trace.api.iast;

import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import datadog.trace.api.iast.sink.HeaderInjectionModule;
import datadog.trace.api.iast.sink.HstsMissingHeaderModule;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.sink.InsecureAuthProtocolModule;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule;
import datadog.trace.api.iast.sink.NoSameSiteCookieModule;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import datadog.trace.api.iast.sink.UntrustedDeserializationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.api.iast.sink.WeakCipherModule;
import datadog.trace.api.iast.sink.WeakHashModule;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import datadog.trace.api.iast.sink.XContentTypeModule;
import datadog.trace.api.iast.sink.XPathInjectionModule;
import datadog.trace.api.iast.sink.XssModule;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bridge between instrumentations and {@link IastModule} instances. */
public abstract class InstrumentationBridge {

  public static StringModule STRING;
  public static CodecModule CODEC;
  public static SqlInjectionModule SQL_INJECTION;
  public static PathTraversalModule PATH_TRAVERSAL;
  public static CommandInjectionModule COMMAND_INJECTION;
  public static WeakCipherModule WEAK_CIPHER;
  public static WeakHashModule WEAK_HASH;
  public static LdapInjectionModule LDAP_INJECTION;
  public static PropagationModule PROPAGATION;
  public static InsecureCookieModule<?> INSECURE_COOKIE;
  public static NoHttpOnlyCookieModule<?> NO_HTTPONLY_COOKIE;
  public static NoSameSiteCookieModule<?> NO_SAMESITE_COOKIE;
  public static SsrfModule SSRF;
  public static UnvalidatedRedirectModule UNVALIDATED_REDIRECT;
  public static WeakRandomnessModule WEAK_RANDOMNESS;
  public static HttpResponseHeaderModule RESPONSE_HEADER_MODULE;
  public static HstsMissingHeaderModule HSTS_MISSING_HEADER_MODULE;
  public static XContentTypeModule X_CONTENT_TYPE_HEADER_MODULE;
  public static TrustBoundaryViolationModule TRUST_BOUNDARY_VIOLATION;
  public static XPathInjectionModule XPATH_INJECTION;
  public static XssModule XSS;
  public static StacktraceLeakModule STACKTRACE_LEAK_MODULE;
  public static HeaderInjectionModule HEADER_INJECTION;
  public static ApplicationModule APPLICATION;
  public static HardcodedSecretModule HARDCODED_SECRET;
  public static InsecureAuthProtocolModule INSECURE_AUTH_PROTOCOL;
  public static ReflectionInjectionModule REFLECTION_INJECTION;
  public static UntrustedDeserializationModule UNTRUSTED_DESERIALIZATION;
  public static EmailInjectionModule EMAIL_INJECTION;

  private static final Map<Class<? extends IastModule>, Field> MODULE_MAP = buildModuleMap();

  private InstrumentationBridge() {}

  public static void registerIastModule(final IastModule module) {
    final Class<? extends IastModule> type = getType(module.getClass());
    final Field field = MODULE_MAP.get(type);
    if (field == null) {
      throw new UnsupportedOperationException("Module not yet supported: " + module.getClass());
    }
    set(field, module);
  }

  /** Used for testing purposes, never use it from production code */
  static <M extends IastModule> M getIastModule(final Class<M> type) {
    final Field field = MODULE_MAP.get(type);
    if (field == null) {
      throw new UnsupportedOperationException("Module not yet supported: " + type);
    }
    return get(field);
  }

  /** Used for testing purposes, never use it from production code */
  static void clearIastModules() {
    MODULE_MAP.values().forEach(it -> set(it, null));
  }

  /** Used for testing purposes, never use it from production code */
  static Set<Class<? extends IastModule>> getIastModules() {
    return MODULE_MAP.keySet();
  }

  @SuppressWarnings("unchecked")
  private static Class<? extends IastModule> getType(final Class<?> module) {
    if (module == null) {
      return null;
    }
    for (final Class<?> ifc : module.getInterfaces()) {
      if (IastModule.class.isAssignableFrom(ifc)) {
        return (Class<? extends IastModule>) ifc;
      }
    }
    return getType(module.getSuperclass());
  }

  @SuppressWarnings("unchecked")
  private static <M extends IastModule> M get(final Field field) {
    try {
      return (M) field.get(null);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  // Field::set() is forbidden because it may be used to mutate final fields, disallowed by
  // https://openjdk.org/jeps/500.
  // However, in this case the method is called on a non-final field, so it is safe.
  @SuppressForbidden
  private static void set(final Field field, final IastModule module) {
    try {
      field.set(null, module);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<Class<? extends IastModule>, Field> buildModuleMap() {
    final List<Field> fields = new ArrayList<>();
    for (final Field field : InstrumentationBridge.class.getDeclaredFields()) {
      final Class<?> module = field.getType();
      final int modifiers = field.getModifiers();
      if (module.isInterface()
          && Modifier.isStatic(modifiers)
          && Modifier.isPublic(modifiers)
          && IastModule.class.isAssignableFrom(module)) {
        fields.add(field);
      }
    }
    final Map<Class<? extends IastModule>, Field> result = new HashMap<>(fields.size());
    for (final Field field : fields) {
      field.setAccessible(true);
      result.put((Class<? extends IastModule>) field.getType(), field);
    }
    return result;
  }
}
