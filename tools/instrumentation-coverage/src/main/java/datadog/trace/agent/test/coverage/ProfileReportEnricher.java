package datadog.trace.agent.test.coverage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adds profiling evidence to an existing self-contained context coverage report. */
public final class ProfileReportEnricher {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private ProfileReportEnricher() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Usage: ProfileReportEnricher <entry-report.json> <profile-analysis.json> <report.html>");
    }
    Path entryReport = Path.of(args[0]);
    Path profileAnalysis = Path.of(args[1]);
    Path output = Path.of(args[2]);
    JsonObject report =
        GSON.fromJson(Files.readString(entryReport, StandardCharsets.UTF_8), JsonObject.class);
    JsonObject profiling =
        GSON.fromJson(Files.readString(profileAnalysis, StandardCharsets.UTF_8), JsonObject.class);
    report.add("profiling", profiling);
    HtmlReport.write(output, GSON.toJson(report));
  }
}
