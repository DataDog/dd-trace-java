package datadog.trace.agent.test.asserts;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.ddagent.TraceMapper;
import datadog.trace.core.DDSpan;
import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.MetaClass;
import groovy.lang.GroovySystem;
import groovy.lang.MissingMethodException;

public class TagsAssert implements GroovyObject {
  private static final Pattern QUEST_MARK_PATTERN = Pattern.compile("\\?");
  private static final Pattern AMPERSAND_PATTERN = Pattern.compile("&");


  private final long spanParentId;
  private final Map<String, Object> tags;
  private final Set<String> assertedTags = new TreeSet<>();
  private transient MetaClass metaClass;

  private TagsAssert(DDSpan span) {
    this.spanParentId = span.getParentId();
    this.tags = span.getTags();
  }

  // --- GroovyObject implementation to enable Groovy-style DSL delegation ---
  @Override
  public MetaClass getMetaClass() {
    if (metaClass == null) {
      metaClass = GroovySystem.getMetaClassRegistry().getMetaClass(getClass());
    }
    return metaClass;
  }

  @Override
  public void setMetaClass(MetaClass metaClass) {
    this.metaClass = metaClass;
  }

  @Override
  public Object invokeMethod(String name, Object args) {
    try {
      // Try normal dispatch first
      return getMetaClass().invokeMethod(this, name, args);
    } catch (MissingMethodException e) {
      // Fallback to our dynamic tag assertion handler
      if (args instanceof Object[]) {
        methodMissing(name, (Object[]) args);
        return null;
      } else {
        methodMissing(name, new Object[] {args});
        return null;
      }
    }
  }

  @Override
  public Object getProperty(String property) {
    return getMetaClass().getProperty(this, property);
  }

  @Override
  public void setProperty(String property, Object newValue) {
    getMetaClass().setProperty(this, property, newValue);
  }

  public static void assertTags(
      DDSpan span, java.util.function.Consumer<TagsAssert> spec, boolean checkAllTags) {

    TagsAssert asserter = new TagsAssert(span);
    spec.accept(asserter);
    if (checkAllTags) {
      asserter.assertTagsAllVerified();
    }
  }

  public static void assertTags(DDSpan span, java.util.function.Consumer<TagsAssert> spec) {

    assertTags(span, spec, true);
  }

  // Groovy-friendly overloads to support the tags { } DSL with methodMissing delegation
  public static void assertTags(
      DDSpan span, groovy.lang.Closure<?> spec, boolean checkAllTags) {

    TagsAssert asserter = new TagsAssert(span);
    spec.setDelegate(asserter);
    spec.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
    spec.call(asserter);
    if (checkAllTags) {
      asserter.assertTagsAllVerified();
    }
  }

  public static void assertTags(DDSpan span, groovy.lang.Closure<?> spec) {

    assertTags(span, spec, true);
  }

  /**
   * Check that, if the peer.service tag source has been set, it matches the provided one.
   *
   * @param sourceTag the source to match
   */
  public void peerServiceFrom(String sourceTag) {
    tag(
        DDTags.PEER_SERVICE_SOURCE,
        (Predicate<Object>)value ->
            SpanNaming.instance().namingSchema().peerService().supports()
                ? Objects.equals(value, sourceTag)
                : value == null);
  }

  public void withCustomIntegrationName(String integrationName) {
    assertedTags.add(DDTags.DD_INTEGRATION);
    Object value = tags.get(DDTags.DD_INTEGRATION);
    if (value == null || !integrationName.equals(value.toString())) {
      throw new AssertionError(
          "Expected " + DDTags.DD_INTEGRATION + "=" + integrationName + " but was " + value);
    }
  }

  public void defaultTagsNoPeerService() {
    defaultTags(false, false);
  }

  public void defaultTagsNoPeerService(boolean distributedRootSpan) {
    defaultTags(distributedRootSpan, false);
  }

  public void isPresent(String name) {
    tag(name, (Predicate<Object>)Objects::nonNull);
  }

  public void arePresent(Collection<String> tagNames) {
    for (String name : tagNames) {
      isPresent(name);
    }
  }

  public void isNotPresent(String name) {
    tag(name, (Predicate<Object>)Objects::isNull);
  }

  public void areNotPresent(Collection<String> tagNames) {
    for (String name : tagNames) {
      isNotPresent(name);
    }
  }

  public void defaultTags() {
    defaultTags(false, true);
  }

  public void defaultTags(boolean distributedRootSpan) {
    defaultTags(distributedRootSpan, true);
  }
  /**
   * @param distributedRootSpan set to true if current span has a parent span but still considered
   *     'root' for current service
   */
  public void defaultTags(boolean distributedRootSpan, boolean checkPeerService) {
    assertedTags.add("thread.name");
    assertedTags.add("thread.id");
    assertedTags.add(DDTags.RUNTIME_ID_TAG);
    assertedTags.add(DDTags.LANGUAGE_TAG_KEY);
    assertedTags.add(RateByServiceTraceSampler.SAMPLING_AGENT_RATE);
    assertedTags.add(TraceMapper.SAMPLING_PRIORITY_KEY.toString());
    assertedTags.add("_sample_rate");
    assertedTags.add(DDTags.PID_TAG);
    assertedTags.add(DDTags.SCHEMA_VERSION_TAG_KEY);
    assertedTags.add(DDTags.PROFILING_ENABLED);
    assertedTags.add(DDTags.PROFILING_CONTEXT_ENGINE);
    assertedTags.add(DDTags.BASE_SERVICE);
    assertedTags.add(DDTags.DSM_ENABLED);
    assertedTags.add(DDTags.DJM_ENABLED);
    assertedTags.add(DDTags.PARENT_ID);
    assertedTags.add(DDTags.SPAN_LINKS); // this is checked by LinksAssert
    for (String t : DDTags.REQUIRED_CODE_ORIGIN_TAGS) {
      assertedTags.add(t);
    }

    if (assertedTags.add(DDTags.DD_INTEGRATION) && tags.get(Tags.COMPONENT) != null) {
      Object component = tags.get(Tags.COMPONENT);
      Object integration = tags.get(DDTags.DD_INTEGRATION);
      if (integration == null || !component.toString().equals(integration.toString())) {
        throw new AssertionError(
            "Component tag and dd.integration tag must match: " + component + " vs " + integration);
      }
    }

    if (tags.get("thread.name") == null) {
      throw new AssertionError("thread.name tag is null");
    }
    if (tags.get("thread.id") == null) {
      throw new AssertionError("thread.id tag is null");
    }

    boolean isRoot = (DDSpanId.ZERO == spanParentId);
    if (isRoot) {
      Object actualSchema = tags.get(DDTags.SCHEMA_VERSION_TAG_KEY);
      Object expectedSchema = SpanNaming.instance().version();
      if (!Objects.equals(actualSchema, expectedSchema)) {
        throw new AssertionError(
            "Schema version mismatch: expected " + expectedSchema + " but was " + actualSchema);
      }
    }

    if (isRoot || distributedRootSpan) {
      if (!Objects.equals(tags.get(DDTags.RUNTIME_ID_TAG), Config.get().getRuntimeId())) {
        throw new AssertionError(
            "Runtime id mismatch: expected "
                + Config.get().getRuntimeId()
                + " but was "
                + tags.get(DDTags.RUNTIME_ID_TAG));
      }
      assertedTags.add(DDTags.TRACER_HOST);
      if (!Objects.equals(tags.get(DDTags.TRACER_HOST), Config.get().getHostName())) {
        throw new AssertionError(
            "Tracer host mismatch: expected "
                + Config.get().getHostName()
                + " but was "
                + tags.get(DDTags.TRACER_HOST));
      }
    } else {
      if (tags.get(DDTags.RUNTIME_ID_TAG) != null) {
        throw new AssertionError("Non-root span should not have " + DDTags.RUNTIME_ID_TAG);
      }
    }

    String spanKind = (String) tags.get(Tags.SPAN_KIND);
    boolean isServer = Tags.SPAN_KIND_SERVER.equals(spanKind);

    if (isRoot || distributedRootSpan || isServer) {
      if (!Objects.equals(tags.get(DDTags.LANGUAGE_TAG_KEY), DDTags.LANGUAGE_TAG_VALUE)) {
        throw new AssertionError(
            "Language tag mismatch: expected "
                + DDTags.LANGUAGE_TAG_VALUE
                + " but was "
                + tags.get(DDTags.LANGUAGE_TAG_KEY));
      }
    } else {
      if (tags.get(DDTags.LANGUAGE_TAG_KEY) != null) {
        throw new AssertionError(
            "Non-root, non-server span should not have " + DDTags.LANGUAGE_TAG_KEY);
      }
    }

    boolean shouldSetPeerService =
        checkPeerService
            && (Tags.SPAN_KIND_CLIENT.equals(spanKind) || Tags.SPAN_KIND_PRODUCER.equals(spanKind));

    if (shouldSetPeerService && SpanNaming.instance().namingSchema().peerService().supports()) {
      assertedTags.add(Tags.PEER_SERVICE);
      assertedTags.add(DDTags.PEER_SERVICE_SOURCE);
      Object peerService = tags.get(Tags.PEER_SERVICE);
      Object source = tags.get(DDTags.PEER_SERVICE_SOURCE);

      if (peerService == null) {
        throw new AssertionError("peer.service tag should not be null");
      }
      Object sourceValue = tags.get(source);
      if (!Objects.equals(peerService, sourceValue)) {
        throw new AssertionError(
            "peer.service mismatch: expected "
                + sourceValue
                + " (from "
                + source
                + ") but was "
                + peerService);
      }
    } else {
      if (tags.get(Tags.PEER_SERVICE) != null) {
        throw new AssertionError("peer.service should be null");
      }
      if (tags.get(DDTags.PEER_SERVICE_SOURCE) != null) {
        throw new AssertionError("peer.service.source should be null");
      }
    }
  }

  public static void codeOriginTags(ListWriter writer) {
    if (Config.get().isDebuggerCodeOriginEnabled()) {
      List<List<DDSpan>> traces = new ArrayList<>(writer);

      List<DDSpan> spans = new ArrayList<>();
      for (List<DDSpan> trace : traces) {
        for (DDSpan span : trace) {
          if (span.getTags().get(DDTags.DD_CODE_ORIGIN_TYPE) != null) {
            spans.add(span);
          }
        }
      }

      if (spans.isEmpty()) {
        throw new AssertionError("Should have found at least one span with code origin");
      }

      for (DDSpan span : spans) {
        assertTags(
            span,
            ta -> {
              for (String tagName : DDTags.REQUIRED_CODE_ORIGIN_TAGS) {
                Object value = ta.tags.get(tagName);
                if (value == null) {
                  throw new AssertionError(
                      "Should have found " + tagName + " in span tags: " + ta.tags.keySet());
                }
              }
            },
            false);
      }
    }
  }

  public void errorTags(Throwable error) {
    errorTags(error.getClass(), error.getMessage());
  }

  public void errorTags(Class<? extends Throwable> errorType) {
    errorTags(errorType, null);
  }

  public void errorTags(Class<? extends Throwable> errorType, Object message) {
    tag(
        "error.type",
        (Predicate<Object>) value -> {
          if (value == null) return false;
          String typeName = value.toString();
          if (errorType.getName().equals(typeName)) {
            return true;
          }
          try {
            Class<?> actual = Class.forName(typeName, false, getClass().getClassLoader());
            return errorType.isAssignableFrom(actual);
          } catch (Throwable ignored) {
            return false;
          }
        });

    tag("error.stack", String.class);

    if (message != null) {
      tag("error.message", message);
    }
  }

  public void urlTags(String url, List<String> queryParams) {
    tag(
        "http.url",
        (Predicate<Object>)value -> {
          try {
            String raw = QUEST_MARK_PATTERN.split(value.toString(), 2)[0];
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            String baseURL = scheme + "://" + host + ":" + port + path;
            return baseURL.equals(url);
          } catch (Exception e) {
            return false;
          }
        });

    tag(
        "http.query.string",
        (Predicate<Object>)value -> {
          String paramString = value == null ? null : value.toString();
          Set<String> spanQueryParams = new HashSet<>();
          if (paramString != null && !paramString.isEmpty()) {
            String[] pairs = AMPERSAND_PATTERN.split(paramString);
            for (String pair : pairs) {
              int idx = pair.indexOf('=');
              if (idx > 0) {
                spanQueryParams.add(pair.substring(0, idx));
              } else {
                spanQueryParams.add(pair);
              }
            }
            if (queryParams != null) {
              for (String param : queryParams) {
                if (!spanQueryParams.contains(param)) {
                  return false;
                }
              }
            }
          } else if (queryParams != null && !queryParams.isEmpty()) {
            return false;
          }
          return true;
        });
  }

  public void tag(String name, Object expected) {
    if (expected == null) {
      return;
    }
    assertedTags.add(name);
    Object value = tag(name);

    if (expected instanceof Pattern) {
      Pattern pattern = (Pattern) expected;
      if (value == null || !pattern.matcher(value.toString()).find()) {
        throw new AssertionError(
            "Tag \""
                + name
                + "\": \""
                + value
                + "\" does not match pattern \""
                + pattern
                + "\"");
      }
    } else if (expected instanceof Class) {
      Class<?> type = (Class<?>) expected;
      if (value == null || !type.isInstance(value)) {
        throw new AssertionError(
            "Tag \""
                + name
                + "\": instance check "
                + type
                + " failed for \""
                + value
                + "\" of class \""
                + (value == null ? "null" : value.getClass())
                + "\"");
      }
    } else if (expected instanceof Predicate) {
      Predicate<Object> predicate = (Predicate<Object>) expected;
      if (!predicate.test(value)) {
        throw new AssertionError(
            "Tag \""
                + name
                + "\": predicate "
                + expected
                + " failed with \""
                + value
                + "\"");
      }
    } else if (expected instanceof groovy.lang.Closure) {
      // Support Groovy closures passed as expected values (e.g., in extraTags)
      groovy.lang.Closure<?> cl = (groovy.lang.Closure<?>) expected;
      java.util.function.Predicate<Object> predicate =
          (v) -> {
            Object res = cl.call(v);
            return res instanceof Boolean ? (Boolean) res : res != null;
          };
      if (!predicate.test(value)) {
        throw new AssertionError(
            "Tag \""
                + name
                + "\": closure predicate "
                + expected
                + " failed with \""
                + value
                + "\"");
      }
    } else if (expected instanceof CharSequence) {
      String expectedStr = expected.toString();
      if (value == null || !expectedStr.equals(value.toString())) {
        throw new AssertionError(
            "Tag \""
                + name
                + "\": \""
                + value
                + "\" != \""
                + expectedStr
                + "\"");
      }
    } else {
      if (!Objects.equals(value, expected)) {
        throw new AssertionError(
            "Tag \""
                + name
                + "\": \""
                + value
                + "\" != \""
                + expected
                + "\"");
      }
    }
  }

  public Object tag(String name) {
    Object t = tags.get(name);
    if (t instanceof CharSequence) {
      return t.toString();
    }
    return t;
  }

  // Support Groovy-style dynamic method calls inside the tags { } DSL, e.g.
  //   "$Tags.COMPONENT" "finatra"
  // which translates to a call to methodMissing("component", ["finatra"]).
  // Enabling this method ensures unqualified tag names are handled by the
  // delegate (TagsAssert) instead of being resolved on the test class.
  public void methodMissing(String name, Object[] args) {
    if (args == null || args.length == 0) {
      throw new IllegalArgumentException(
          "No value provided for dynamic tag assertion " + name);
    }

    Object arg = args[0];
    if (arg instanceof java.util.regex.Pattern) {
      tag(name, (java.util.regex.Pattern) arg);
      return;
    }
    if (arg instanceof Class<?>) {
      tag(name, (Class<?>) arg);
      return;
    }
    if (arg instanceof java.util.function.Predicate) {
      // noinspection unchecked
      tag(name, (java.util.function.Predicate<Object>) arg);
      return;
    }
    if (arg instanceof groovy.lang.Closure) {
      groovy.lang.Closure<?> cl = (groovy.lang.Closure<?>) arg;
      tag(
          name,
          (java.util.function.Predicate<Object>)
              (value) -> {
                Object res = cl.call(value);
                return res instanceof Boolean ? (Boolean) res : res != null;
              });
      return;
    }
    // Fallback to string comparison
    tag(name, arg == null ? null : arg.toString());
  }

  public boolean addTags(Map<String, Serializable> extraTags) {
    for (Map.Entry<String, Serializable> e : extraTags.entrySet()) {
      tag(e.getKey(), e.getValue());
    }
    return true;
  }

  public void assertTagsAllVerified() {
    Set<String> remaining = new TreeSet<>(tags.keySet());
    remaining.removeAll(assertedTags);
    if (!remaining.isEmpty()) {
      throw new AssertionError(
          "Unverified tags remain: "
              + remaining
              + " all="
              + tags.keySet()
              + " asserted="
              + assertedTags);
    }
  }
}
