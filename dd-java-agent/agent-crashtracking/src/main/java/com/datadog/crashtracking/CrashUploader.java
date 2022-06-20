package com.datadog.crashtracking;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Crash Reporter implementation */
public class CrashUploader {

  private static final Logger log = LoggerFactory.getLogger(CrashUploader.class);

  private final Tracer tracer;

  /**
   * Main entry point into crash reporter. This gets invoked through -XX:OnError="java
   * com.datadog.crashtracking.agent.CrashUploader ..."
   */
  public static void main(String[] args) throws IOException {
    final Tracer tracer = GlobalTracer.get();

    new CrashUploader(tracer).upload(args);
  }

  public CrashUploader() {
    this(GlobalTracer.get());
  }

  CrashUploader(final Tracer tracer) {
    this.tracer = tracer;
  }

  void upload(String[] files) {
    for (String file : files) {
      try (InputStream stream = new FileInputStream(file);
          Reader reader = new BufferedReader(new InputStreamReader(stream))) {
        Span span = tracer.buildSpan("crash-catching").start();
        try (Scope scope = tracer.activateSpan(span)) {
          span.setTag(Tags.ERROR, true);
          span.log(Collections.singletonMap(Fields.MESSAGE, readContent(reader)));
        } finally {
          span.finish();
        }
      } catch (FileNotFoundException | SecurityException e) {
        log.error("Failed to open {}", file, e);
      } catch (IOException e) {
        log.error("Failed to read {}", file, e);
      } catch (Throwable t) {
        log.error("Failed to process {}", file, t);
      }
    }
  }

  String readContent(Reader reader) throws IOException {
    StringBuilder sb = new StringBuilder();

    char[] buffer = new char[1 << 16];
    int read = 0;
    while ((read = reader.read(buffer)) != -1) {
      sb.append(buffer, 0, read);
    }

    return sb.toString();
  }
}
