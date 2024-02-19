package datadog.trace.instrumentation.xxl_job_2_3x;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class JobConstants {
  public static final CharSequence XXL_JOB_REQUEST = UTF8BytesString.create("xxl-job");

  public static final CharSequence XXL_JOB_SERVER = UTF8BytesString.create("xxl-job");

  public static final String INSTRUMENTATION_NAME = "xxl-job";

  public static final String JOB_PARAM = "job.param";

  public static final String JOB_TYPE = "job.type";

  public static final String JOB_METHOD = "job.method";

  public static final String JOB_ID = "job.id";

  public static final String CMD = "job.cmd";
  public static final String JOB_CODE = "job.code";

  interface JobType {
    String SIMPLE_JOB = "simple-job";
    String METHOD_JOB = "method-job";
    String SCRIPT_JOB = "script-job";
  }

  interface HandleClassName {
    String HANDLER_CLASS  = "com.xxl.job.core.handler.IJobHandler";
    String METHOD_CLASS   = "com.xxl.job.core.handler.impl.MethodJobHandler";
    String SCRIPT_CLASS   = "com.xxl.job.core.handler.impl.ScriptJobHandler";
    String GLUE_CLASS     = "com.xxl.job.core.handler.impl.GlueJobHandler";
  }
}
