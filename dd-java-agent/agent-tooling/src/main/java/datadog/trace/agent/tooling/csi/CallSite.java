package datadog.trace.agent.tooling.csi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface CallSite {

  /** Interface to be used for SPI injection, by default {@link CallSiteAdvice} */
  Class<?> spi() default CallSiteAdvice.class;

  /** Helper classes for the advice */
  Class<?>[] helpers() default {};

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @interface After {
    /**
     * Pointcut expression for the advice (e.g. {@code java.lang.StringBuilder
     * java.lang.StringBuilder.append(java.lang.String)})
     */
    String value();
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @interface Around {
    /**
     * Pointcut expression for the advice (e.g. {@code java.lang.StringBuilder
     * java.lang.StringBuilder.append(java.lang.String)})
     */
    String value();
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @interface Before {
    /**
     * Pointcut expression for the advice (e.g. {@code java.lang.StringBuilder
     * java.lang.StringBuilder.append(java.lang.String)})
     */
    String value();
  }
}
