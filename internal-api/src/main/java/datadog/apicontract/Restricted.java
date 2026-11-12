package datadog.apicontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a member that is public only because Java has no narrower visibility across modules: it may
 * be used only from the packages in {@link #allowedIn}. It typically trades a safety check for
 * speed, trusting its caller to have made the check already.
 *
 * <p>This marker changes no runtime behavior. Instrumentation modules are kept out by the
 * forbidden-APIs signatures in {@code gradle/forbiddenApiFilters/instrumentation.txt}, which must
 * list every {@code @Restricted} member.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface Restricted {
  /** Package prefixes allowed to use the member, such as {@code datadog.trace.core}. */
  String[] allowedIn();
}
