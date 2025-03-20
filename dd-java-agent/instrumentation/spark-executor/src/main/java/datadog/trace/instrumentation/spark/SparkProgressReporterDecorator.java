package datadog.trace.instrumentation.spark;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.datasources.v2.WriteToDataSourceV2Exec;
import org.apache.spark.sql.execution.streaming.BaseStreamingSource;
import org.apache.spark.sql.execution.streaming.IncrementalExecution;
import org.apache.spark.sql.execution.streaming.MicroBatchExecution;
import org.apache.spark.sql.execution.streaming.sources.MicroBatchWriter;
import org.apache.spark.sql.kafka010.KafkaMicroBatchReader;
import org.apache.spark.sql.kafka010.KafkaSourceOffset;
import org.apache.spark.sql.kafka010.KafkaStreamWriter;
import org.apache.spark.sql.sources.v2.writer.DataSourceWriter;
import org.apache.spark.sql.sources.v2.writer.streaming.StreamWriter;
import scala.collection.JavaConverters;

public class SparkProgressReporterDecorator extends BaseDecorator {
  public static final CharSequence SPARK_PROGRESS_REPORTER =
      UTF8BytesString.create("spark.progress.reporter");
  public static SparkProgressReporterDecorator DECORATE = new SparkProgressReporterDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spark-executor"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return UTF8BytesString.create("spark");
  }

  public void onStreamingQueryProgress(AgentSpan span, Object reporter) {
    System.out.println("#### - Got reporter: " + reporter.getClass().getName());
    if (reporter instanceof MicroBatchExecution) {
      MicroBatchExecution microBatchExecution = (MicroBatchExecution) reporter;
      System.out.println("#### - Got sink: " + microBatchExecution.sink().getClass().getName());
      // process sources
      for (BaseStreamingSource source :
          JavaConverters.asJavaCollection(microBatchExecution.sources())) {
        System.out.println("#### - Got Source: " + source.getClass().getName());
        if (source instanceof KafkaMicroBatchReader) {
          KafkaMicroBatchReader reader = (KafkaMicroBatchReader) source;
          KafkaSourceOffset start = (KafkaSourceOffset) reader.getStartOffset();
          KafkaSourceOffset end = (KafkaSourceOffset) reader.getEndOffset();

          System.out.println("#### Kafka source " + start + ", " + end);
        } else {
          System.out.println("#### Unknown source " + source.getClass().getName());
        }
      }

      IncrementalExecution incrementalExecution = microBatchExecution.lastExecution();
      SparkPlan plan = incrementalExecution.executedPlan();
      if (plan instanceof WriteToDataSourceV2Exec) {
        WriteToDataSourceV2Exec writePlan = (WriteToDataSourceV2Exec) plan;
        DataSourceWriter dataSourceWriter = writePlan.writer();
        if (dataSourceWriter instanceof MicroBatchWriter) {
          MicroBatchWriter microBatchWriter = (MicroBatchWriter) dataSourceWriter;

          StreamWriter streamWriter = microBatchWriter.writer();
          if (streamWriter instanceof KafkaStreamWriter) {
            KafkaStreamWriter kafkaStreamWriter = (KafkaStreamWriter) streamWriter;
            System.out.println("#### KafkaStreamWriter " + kafkaStreamWriter);
          }
        }
      }
    }
  }
}
