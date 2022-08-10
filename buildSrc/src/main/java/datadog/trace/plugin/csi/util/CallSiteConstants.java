package datadog.trace.plugin.csi.util;

import org.objectweb.asm.Opcodes;

public abstract class CallSiteConstants {
  private CallSiteConstants() {}

  public static final String CALL_SITE_PACKAGE = "datadog.trace.agent.tooling.csi";
  public static final String CALL_SITE_ANNOTATION = CALL_SITE_PACKAGE + ".CallSite";
  public static final String BEFORE_ANNOTATION = CALL_SITE_ANNOTATION + "$Before";
  public static final String AROUND_ANNOTATION = CALL_SITE_ANNOTATION + "$Around";
  public static final String AFTER_ANNOTATION = CALL_SITE_ANNOTATION + "$After";
  public static final String CALL_SITE_ADVICE_CLASS = CALL_SITE_PACKAGE + ".CallSiteAdvice";

  public static final String BYTE_BUDDY_PACKAGE = "net.bytebuddy.asm";
  public static final String BYTE_BUDDY_ADVICE_CLASS = BYTE_BUDDY_PACKAGE + ".Advice";
  public static final String THIS_ANNOTATION = BYTE_BUDDY_ADVICE_CLASS + "$This";
  public static final String ARGUMENT_ANNOTATION = BYTE_BUDDY_ADVICE_CLASS + "$Argument";
  public static final String RETURN_ANNOTATION = BYTE_BUDDY_ADVICE_CLASS + "$Return";

  public static final String CONSTRUCTOR_METHOD = "<init>";

  /**
   * {@link datadog.trace.plugin.csi.ValidationContext} property name for the {@link
   * datadog.trace.plugin.csi.TypeResolver}
   */
  public static final String TYPE_RESOLVER = "typeResolver";

  /** ASM version to use in all CSI related tasks */
  public static final int ASM_API_VERSION = Opcodes.ASM7;
}
