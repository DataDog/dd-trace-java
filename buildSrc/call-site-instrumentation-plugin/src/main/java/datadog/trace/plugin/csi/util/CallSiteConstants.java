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
  public static final String POINTCUT_TYPE = "Pointcut";
  public static final String POINTCUT_FQCN = CALL_SITE_PACKAGE + "." + POINTCUT_TYPE;
  public static final String INVOKE_DYNAMIC_CONSTANTS_ANNOTATION =
      CALL_SITE_ANNOTATION + "$InvokeDynamicConstants";

  public static final String ARGUMENT_ANNOTATION = CALL_SITE_ANNOTATION + "$Argument";
  public static final String RETURN_ANNOTATION = CALL_SITE_ANNOTATION + "$Return";
  public static final String CALL_SITE_ADVICE_CLASS = "CallSiteAdvice";

  public static final String CALL_SITE_ADVICE_FQCN =
      CALL_SITE_PACKAGE + "." + CALL_SITE_ADVICE_CLASS;

  public static final String INVOKE_ADVICE_CLASS = CALL_SITE_PACKAGE + ".InvokeAdvice";

  public static final String INVOKE_DYNAMIC_ADVICE_CLASS =
      CALL_SITE_PACKAGE + ".InvokeDynamicAdvice";

  public static final String STACK_DUP_MODE_CLASS = "StackDupMode";

  public static final String HAS_FLAGS_CLASS = CALL_SITE_ADVICE_CLASS + ".HasFlags";

  public static final String HAS_MIN_JAVA_VERSION_CLASS =
      CALL_SITE_ADVICE_CLASS + ".HasMinJavaVersion";

  public static final String HAS_HELPERS_CLASS = CALL_SITE_ADVICE_CLASS + ".HasHelpers";

  public static final String METHOD_HANDLER_CLASS = "MethodHandler";

  public static final String CONSTRUCTOR_METHOD = "<init>";

  /**
   * {@link datadog.trace.plugin.csi.ValidationContext} property name for the {@link
   * datadog.trace.plugin.csi.TypeResolver}
   */
  public static final String TYPE_RESOLVER = "typeResolver";

  /** ASM version to use in all CSI related tasks */
  public static final int ASM_API_VERSION = Opcodes.ASM7;

  public static final String AUTO_SERVICE_CLASS = "com.google.auto.service.AutoService";

  public static final String HANDLE_CLASS = "net.bytebuddy.jar.asm.Handle";

  public static final String OPCODES_CLASS = "net.bytebuddy.jar.asm.Opcodes";
}
