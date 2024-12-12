package datadog.trace.agent.tooling.csi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface CallSite {

  /** Interfaces to be used for SPI injection */
  Class<?>[] spi();

  /** Enable or disable the call site: (fully qualified name, method name and arguments) */
  String[] enabled() default {};

  /** Helper classes for the advice */
  Class<?>[] helpers() default {};

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @Repeatable(AfterArray.class)
  @interface After {
    /**
     * Pointcut expression for the advice (e.g. {@code java.lang.StringBuilder
     * java.lang.StringBuilder.append(java.lang.String)})
     */
    String value();

    boolean invokeDynamic() default false;
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @interface AfterArray {
    After[] value();
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @Repeatable(AroundArray.class)
  @interface Around {
    /**
     * Pointcut expression for the advice (e.g. {@code java.lang.StringBuilder
     * java.lang.StringBuilder.append(java.lang.String)})
     */
    String value();

    boolean invokeDynamic() default false;
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @interface AroundArray {
    Around[] value();
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @Repeatable(BeforeArray.class)
  @interface Before {
    /**
     * Pointcut expression for the advice (e.g. {@code java.lang.StringBuilder
     * java.lang.StringBuilder.append(java.lang.String)})
     */
    String value();

    boolean invokeDynamic() default false;
  }

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.CLASS)
  @interface BeforeArray {
    Before[] value();
  }

  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.CLASS)
  @interface This {}

  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.CLASS)
  @interface Argument {
    int value() default -1;
  }

  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.CLASS)
  @interface AllArguments {
    boolean includeThis() default false;
  }

  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.CLASS)
  @interface InvokeDynamicConstants {}

  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.CLASS)
  @interface Return {}
}
