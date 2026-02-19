package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.spark.scheduler.AccumulableInfo;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import scala.Tuple2;
import scala.collection.JavaConverters;

public class SparkSQLUtils {
  public static void addSQLPlanToStageSpan(
      AgentSpan span,
      SparkPlanInfo plan,
      Map<Long, Integer> accumulators,
      SparkAggregatedTaskMetrics stageMetric,
      int stageId) {
    Set<Integer> parentStageIds = new HashSet<>();
    SparkPlanInfoForStage planForStage =
        computeStageInfoForStage(plan, accumulators, stageId, parentStageIds, false);

    span.setTag("_dd.spark.sql_parent_stage_ids", parentStageIds.toString());

    if (planForStage != null) {
      String json = planForStage.toJson(stageMetric);
      span.setTag("_dd.spark.sql_plan", json);
    }
  }

  public static SparkPlanInfoForStage computeStageInfoForStage(
      SparkPlanInfo plan,
      Map<Long, Integer> accumulators,
      int stageId,
      Set<Integer> parentStageIds,
      boolean foundStage) {
    Set<Integer> stageIds = stageIdsForPlan(plan, accumulators);

    boolean hasStageInfo = !stageIds.isEmpty();
    boolean isForStage = stageIds.contains(stageId);

    if (foundStage && hasStageInfo && !isForStage) {
      parentStageIds.addAll(stageIds);

      // Stopping the propagation since this node is for another stage
      return null;
    }

    Collection<SparkPlanInfo> children =
        AbstractDatadogSparkListener.listener.getPlanInfoChildren(plan);

    if (foundStage || isForStage) {
      // The expected stage was found, adding its children to the plan
      List<SparkPlanInfoForStage> childrenForStage = new ArrayList<>();
      for (SparkPlanInfo child : children) {
        SparkPlanInfoForStage planForStage =
            computeStageInfoForStage(child, accumulators, stageId, parentStageIds, true);

        if (planForStage != null) {
          childrenForStage.add(planForStage);
        }
      }

      return new SparkPlanInfoForStage(plan, childrenForStage);
    } else {
      // The expected stage was not found yet, searching in the children nodes
      for (SparkPlanInfo child : children) {
        SparkPlanInfoForStage planForStage =
            computeStageInfoForStage(child, accumulators, stageId, parentStageIds, false);

        if (planForStage != null) {
          // Early stopping if the stage was found, no need to keep searching
          return planForStage;
        }
      }
    }

    return null;
  }

  private static Set<Integer> stageIdsForPlan(SparkPlanInfo info, Map<Long, Integer> accumulators) {
    Set<Integer> stageIds = new HashSet<>();

    Collection<SQLMetricInfo> metrics =
        AbstractDatadogSparkListener.listener.getPlanInfoMetrics(info);
    for (SQLMetricInfo metric : metrics) {
      // Using the accumulators to associate a plan with its stage
      Integer stageId = accumulators.get(metric.accumulatorId());

      if (stageId != null) {
        stageIds.add(stageId);
      }
    }

    return stageIds;
  }

  public static class AccumulatorWithStage {
    private final int stageId;
    private final AccumulableInfo acc;

    public AccumulatorWithStage(int stageId, AccumulableInfo acc) {
      this.stageId = stageId;
      this.acc = acc;
    }

    public void toJson(JsonGenerator generator, SQLMetricInfo metric) throws IOException {
      if (acc.name().isDefined() && acc.value().isDefined()) {
        String name = acc.name().get();
        Long value = null;
        try {
          // As of spark 3.5, all SQL metrics are Long, safeguard if it changes in new versions
          value = (Long) acc.value().get();
        } catch (ClassCastException ignored) {
        }

        if (name != null && value != null) {
          generator.writeStartObject();
          generator.writeNumberField(name, value);
          generator.writeStringField("type", metric.metricType());
          generator.writeEndObject();
        }
      }
    }
  }

  public static class SparkPlanInfoForStage {
    private final SparkPlanInfo plan;
    private final List<SparkPlanInfoForStage> children;

    public SparkPlanInfoForStage(SparkPlanInfo plan, List<SparkPlanInfoForStage> children) {
      this.plan = plan;
      this.children = children;
    }

    public String toJson(SparkAggregatedTaskMetrics stageMetric) {
      // Using the jackson JSON lib used by spark
      // https://mvnrepository.com/artifact/org.apache.spark/spark-core_2.12/3.5.0
      ObjectMapper mapper =
          new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (JsonGenerator generator = mapper.getFactory().createGenerator(baos)) {
        this.toJson(generator, mapper, stageMetric);
      } catch (IOException e) {
        return null;
      }

      return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void toJson(
        JsonGenerator generator, ObjectMapper mapper, SparkAggregatedTaskMetrics stageMetric)
        throws IOException {
      generator.writeStartObject();
      generator.writeStringField("node", plan.nodeName());
      generator.writeNumberField("nodeId", plan.hashCode());

      String nodeDetails = plan.simpleString();
      if (nodeDetails.startsWith(plan.nodeName())) {
        nodeDetails = nodeDetails.substring(plan.nodeName().length()).trim();
      }
      if (!nodeDetails.isEmpty()) {
        generator.writeStringField("nodeDetailString", nodeDetails);
      }

      // Metadata is only added natively by Spark for FileSourceScan nodes
      // We leverage this to extract & inject additional argument-level data
      if (!plan.metadata().isEmpty()) {
        generator.writeFieldName("meta");
        generator.writeStartObject();

        for (Tuple2<String, String> metadata : JavaConverters.asJavaCollection(plan.metadata())) {
          generator.writeFieldName(metadata._1);
          try {
            generator.writeTree(mapper.readTree(metadata._2));
          } catch (IOException e) {
            generator.writeString(metadata._2);
          }
        }

        generator.writeEndObject();
      }

      List<SQLMetricInfo> metrics = AbstractDatadogSparkListener.listener.getPlanInfoMetrics(plan);

      // Writing final values of metrics
      if (!metrics.isEmpty()) {
        generator.writeFieldName("metrics");
        generator.writeStartArray();
        for (SQLMetricInfo metric : metrics) {
          stageMetric.externalAccumToJson(generator, metric);
        }
        generator.writeEndArray();
      }

      // Writing child nodes
      if (!children.isEmpty()) {
        generator.writeFieldName("children");
        generator.writeStartArray();
        for (SparkPlanInfoForStage child : children) {
          child.toJson(generator, mapper, stageMetric);
        }
        generator.writeEndArray();
      }

      generator.writeEndObject();
    }
  }
}
