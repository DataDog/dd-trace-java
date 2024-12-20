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
import org.apache.spark.sql.catalyst.plans.logical.AppendData;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.PartialFunction;
import scala.Tuple2;
import scala.collection.JavaConverters;

public class SparkSQLUtils {
  private static final Logger log = LoggerFactory.getLogger(SparkSQLUtils.class);

  public static void addSQLPlanToStageSpan(
      AgentSpan span,
      SparkPlanInfo plan,
      Map<Long, AccumulatorWithStage> accumulators,
      int stageId) {
    Set<Integer> parentStageIds = new HashSet<>();
    SparkPlanInfoForStage planForStage =
        computeStageInfoForStage(plan, accumulators, stageId, parentStageIds, false);

    span.setTag("_dd.spark.sql_parent_stage_ids", parentStageIds.toString());

    if (planForStage != null) {
      String json = planForStage.toJson(accumulators);
      span.setTag("_dd.spark.sql_plan", json);
    }
  }

  public static SparkPlanInfoForStage computeStageInfoForStage(
      SparkPlanInfo plan,
      Map<Long, AccumulatorWithStage> accumulators,
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

  private static Set<Integer> stageIdsForPlan(
      SparkPlanInfo info, Map<Long, AccumulatorWithStage> accumulators) {
    Set<Integer> stageIds = new HashSet<>();

    Collection<SQLMetricInfo> metrics =
        AbstractDatadogSparkListener.listener.getPlanInfoMetrics(info);
    for (SQLMetricInfo metric : metrics) {
      // Using the accumulators to associate a plan with its stage
      AccumulatorWithStage acc = accumulators.get(metric.accumulatorId());

      if (acc != null) {
        stageIds.add(acc.stageId);
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

    public String toJson(Map<Long, AccumulatorWithStage> accumulators) {
      // Using the jackson JSON lib used by spark
      // https://mvnrepository.com/artifact/org.apache.spark/spark-core_2.12/3.5.0
      ObjectMapper mapper =
          new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (JsonGenerator generator = mapper.getFactory().createGenerator(baos)) {
        this.toJson(generator, accumulators);
      } catch (IOException e) {
        return null;
      }

      return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void toJson(JsonGenerator generator, Map<Long, AccumulatorWithStage> accumulators)
        throws IOException {
      generator.writeStartObject();
      generator.writeStringField("node", plan.nodeName());
      generator.writeNumberField("nodeId", plan.hashCode());

      // Metadata is only present for FileSourceScan nodes
      if (!plan.metadata().isEmpty()) {
        generator.writeFieldName("meta");
        generator.writeStartObject();

        for (Tuple2<String, String> metadata : JavaConverters.asJavaCollection(plan.metadata())) {
          generator.writeStringField(metadata._1, metadata._2);
        }

        generator.writeEndObject();
      }

      List<SQLMetricInfo> metrics = AbstractDatadogSparkListener.listener.getPlanInfoMetrics(plan);

      // Writing final values of metrics
      if (!metrics.isEmpty()) {
        generator.writeFieldName("metrics");
        generator.writeStartArray();
        for (SQLMetricInfo metric : metrics) {
          long accumulatorId = metric.accumulatorId();
          AccumulatorWithStage acc = accumulators.get(accumulatorId);
          if (acc != null) {
            acc.toJson(generator, metric);
          }
        }
        generator.writeEndArray();
      }

      // Writing child nodes
      if (!children.isEmpty()) {
        generator.writeFieldName("children");
        generator.writeStartArray();
        for (SparkPlanInfoForStage child : children) {
          child.toJson(generator, accumulators);
        }
        generator.writeEndArray();
      }

      generator.writeEndObject();
    }
  }

  static class LineageDataset {
    final String name;
    final String schema;
    final String properties;
    final String stats;
    final String type;

    public LineageDataset(
        String name, String schema, String stats, String properties, String type) {
      this.name = name;
      this.schema = schema;
      this.properties = properties;
      this.stats = stats;
      this.type = type;
    }

    public LineageDataset(String name, String schema, String stats, String properties) {
      this.name = name;
      this.schema = schema;
      this.properties = properties;
      this.stats = stats;
      this.type = "unknown";
    }
  }

  static PartialFunction<LogicalPlan, DataSourceV2Relation> pf =
      new PartialFunction<LogicalPlan, DataSourceV2Relation>() {
        @Override
        public boolean isDefinedAt(LogicalPlan x) {
          return x instanceof DataSourceV2Relation;
        }

        @Override
        public DataSourceV2Relation apply(LogicalPlan x) {
          return (DataSourceV2Relation) x;
        }
      };

  static PartialFunction<LogicalPlan, LineageDataset> logicalPlanToDataset =
      new PartialFunction<LogicalPlan, LineageDataset>() {
        @Override
        public boolean isDefinedAt(LogicalPlan x) {
          return x instanceof DataSourceV2Relation
              || (x instanceof AppendData
                  && ((AppendData) x).table() instanceof DataSourceV2Relation);
        }

        @Override
        public LineageDataset apply(LogicalPlan x) {
          try {
            if (x instanceof DataSourceV2Relation) {
              DataSourceV2Relation relation = (DataSourceV2Relation) x;
              return new LineageDataset(
                  relation.table().name(),
                  relation.schema().json(),
                  "",
                  relation.table().properties().toString(),
                  "input");
            } else if (x instanceof AppendData) {
              AppendData appendData = (AppendData) x;
              DataSourceV2Relation relation = (DataSourceV2Relation) appendData.table();
              return new LineageDataset(
                  relation.table().name(),
                  relation.schema().json(),
                  "",
                  relation.table().properties().toString(),
                  "output");
            }
          } catch (Exception e) {
            log.debug("Error while converting logical plan to dataset", e);
            return null;
          }

          return null;
        }
      };
}
