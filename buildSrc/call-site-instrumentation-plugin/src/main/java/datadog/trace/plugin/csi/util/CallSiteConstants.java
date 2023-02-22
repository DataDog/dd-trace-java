package datadog.trace.plugin.csi.util;

import org.objectweb.asm.Opcodes;

public abstract class CallSiteConstants {
  private CallSiteConstants() {}

  public static final String CALL_SITE_PACKAGE = "datadog.trace.agent.tooling.csi";
  public static final String CALL_SITE_ANNOTATION = CALL_SITE_PACKAGE + ".CallSite";
  public static final String BEFORE_ANNOTATION = CALL_SITE_ANNOTATION + "$Before";
  public static final String BEFORE_ARRAY_ANNOTATION = CALL_SITE_ANNOTATION + "$BeforeArray";
  public static final String AROUND_ANNOTATION = CALL_SITE_ANNOTATION + "$Around";
  public static final String AROUND_ARRAY_ANNOTATION = CALL_SITE_ANNOTATION + "$AroundArray";
  public static final String AFTER_ANNOTATION = CALL_SITE_ANNOTATION + "$After";
  public static final String AFTER_ARRAY_ANNOTATION = CALL_SITE_ANNOTATION + "$AfterArray";
  public static final String THIS_ANNOTATION = CALL_SITE_ANNOTATION + "$This";
  public static final String ALL_ARGS_ANNOTATION = CALL_SITE_ANNOTATION + "$AllArguments";

  public static final String INVOKE_DYNAMIC_CONSTANTS_ANNOTATION =
      CALL_SITE_ANNOTATION + "$InvokeDynamicConstants";

  public static final String ARGUMENT_ANNOTATION = CALL_SITE_ANNOTATION + "$Argument";
  public static final String RETURN_ANNOTATION = CALL_SITE_ANNOTATION + "$Return";
  public static final String CALL_SITE_ADVICE_CLASS = CALL_SITE_PACKAGE + ".CallSiteAdvice";

  public static final String CONSTRUCTOR_METHOD = "<init>";

  /**
   * {@link datadog.trace.plugin.csi.ValidationContext} property name for the {@link
   * datadog.trace.plugin.csi.TypeResolver}
   */
  public static final String TYPE_RESOLVER = "typeResolver";

  /** ASM version to use in all CSI related tasks */
  public static final int ASM_API_VERSION = Opcodes.ASM7;
}
