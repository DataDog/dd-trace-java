package datadog.trace.instrumentation.powerjob;

public class PowerjobConstants {

  public static final String INSTRUMENTATION_NAME = "powerjob";

  interface Processer{

     String BASIC_PROCESSOR = "tech.powerjob.worker.core.processor.sdk.BasicProcessor";
     String BROADCAST_PROCESSOR = "tech.powerjob.worker.core.processor.sdk.BroadcastProcessor";
     String MAP_PROCESSOR = "tech.powerjob.worker.core.processor.sdk.MapProcessor";
     String MAP_REDUCE_PROCESSOR = "tech.powerjob.worker.core.processor.sdk.MapReduceProcessor";
  }

  interface Tags{
    String JOB_ID ="job_id";
    String INSTANCE_ID ="instance_id";
    String TASK_ID ="task_id";
    String TASK_NAME ="task_name";
    String JOB_PARAM ="job_param";
    String PROCESS_TYPE = "process_type";

  }

  interface ProcessType{
    String BASE = "base";
    String MAP = "map";
    String MAP_REDUCE = "map_reduce";
    String BROADCAST = "broadcast";
  }
}
