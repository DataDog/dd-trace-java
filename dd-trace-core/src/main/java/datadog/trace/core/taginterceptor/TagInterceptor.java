package datadog.trace.core.taginterceptor;

import static datadog.trace.api.KnownTags.COMPONENT_ID;
import static datadog.trace.api.KnownTags.LANGUAGE_ID;
import static datadog.trace.api.KnownTags.SERVLET_CONTEXT_ID;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_TAGS;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.STATUS_404;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.STATUS_404_DECORATOR;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.URL_AS_RESOURCE_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.Pair;
import datadog.trace.api.SpanTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.core.DDSpanContext;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TagInterceptor {

  final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
  private final boolean splitByServletContext;
  private final String inferredServiceName;

  /**
   * Whether each known tag, indexed by serial, is a split-by-tags entry. Sized to every known
   * serial and never changed after construction, so the check is a single load.
   */
  private final boolean[] splitServiceSerials;

  /** split-by-tags entries naming custom tags; null when there are none. */
  private final Set<String> splitServiceCustomNames;

  final boolean shouldSet404ResourceName;
  final boolean shouldSetUrlResourceAsName;
  private final boolean jeeSplitByDeployment;

  public TagInterceptor(RuleFlags ruleFlags) {
    this(
        Config.get().isServiceNameSetByUser(),
        CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME),
        Config.get().getSplitByTags(),
        ruleFlags,
        Config.get().isJeeSplitByDeployment());
  }

  public TagInterceptor(
      boolean isServiceNameSetByUser,
      String inferredServiceName,
      Set<String> splitServiceTags,
      RuleFlags ruleFlags,
      boolean jeeSplitByDeployment) {
    this.isServiceNameSetByUser = isServiceNameSetByUser;
    this.inferredServiceName = inferredServiceName;
    this.splitServiceSerials = knownTagSerials(splitServiceTags);
    this.splitServiceCustomNames = customTagNames(splitServiceTags);
    this.ruleFlags = ruleFlags;
    splitByServletContext = isSplitServiceTag(SERVLET_CONTEXT_ID);

    shouldSet404ResourceName =
        ruleFlags.isEnabled(URL_AS_RESOURCE_NAME)
            && ruleFlags.isEnabled(STATUS_404)
            && ruleFlags.isEnabled(STATUS_404_DECORATOR);
    shouldSetUrlResourceAsName = ruleFlags.isEnabled(URL_AS_RESOURCE_NAME);
    this.jeeSplitByDeployment = jeeSplitByDeployment;
  }

  /**
   * Marks, by serial, each known tag that {@code names} names under any of its names, so a tag set
   * by id or by its OpenTelemetry name matches too.
   */
  private static boolean[] knownTagSerials(Set<String> names) {
    boolean[] serials = new boolean[KnownTagCodec.serialLimit()];
    if (names.isEmpty()) {
      return serials;
    }
    Set<String> canonicalNames = new HashSet<>();
    for (String name : names) {
      canonicalNames.add(KnownTagCodec.canonicalTagName(name));
    }
    for (int serial = 1; serial < serials.length; ++serial) {
      serials[serial] =
          canonicalNames.contains(KnownTagCodec.nameOf(KnownTagCodec.makeTagId(serial)));
    }
    return serials;
  }

  /** The names in {@code names} that do not resolve to a known tag, or null when there are none. */
  private static Set<String> customTagNames(Set<String> names) {
    Set<String> custom = null;
    for (String name : names) {
      if (KnownTagCodec.keyOf(name) == 0) {
        if (custom == null) {
          custom = new HashSet<>();
        }
        custom.add(name);
      }
    }
    return custom;
  }

  /** Requires a known id, whose serial is always in range of the table. */
  boolean isSplitServiceTag(long tagId) {
    return splitServiceSerials[KnownTagCodec.serialNum(tagId)];
  }

  private boolean isSplitServiceTag(String customTag) {
    Set<String> names = splitServiceCustomNames;
    return names != null && names.contains(customTag);
  }

  /**
   * Whether {@code map} must be copied onto a span entry by entry: some entry may be routed, or
   * holds a direction-dependent name, which the span resolves through {@link SpanTags}. Computed
   * once for a tracer-level map, so a map with neither takes the bulk-copy fast path.
   */
  public boolean needsIntercept(TagMap map) {
    for (TagMap.EntryReader entry : map) {
      if (needsIntercept(entry) || SpanTags.isDirectionDependent(entry)) return true;
    }
    return false;
  }

  public boolean needsIntercept(TagMap.EntryReader entry) {
    long tagId = entry.tagId();
    return tagId != 0 ? needsIntercept(tagId) : isSplitServiceTag(entry.tag());
  }

  /** Whether a custom tag (one with no id) may be routed; only split-by-tags routes them. */
  public boolean needsInterceptCustomTag(String customTag) {
    return isSplitServiceTag(customTag);
  }

  public boolean needsIntercept(Map<String, ?> map) {
    for (String tag : map.keySet()) {
      if (needsIntercept(tag)) return true;
    }
    return false;
  }

  /**
   * Whether {@link #interceptTag(DDSpanContext, long, Object)} may route the tag, or must see it to
   * supersede a pending direction-scoped alias ({@link KnownTagCodec#HAS_SCOPED_ALIAS}). With a
   * constant id, the bit test folds to a constant, leaving only the split-by-tags table load at run
   * time. {@code tagId} must be a known id.
   */
  public boolean needsIntercept(long tagId) {
    return needsIntercept(
        tagId, (int) (KnownTagCodec.INTERCEPTED | KnownTagCodec.HAS_SCOPED_ALIAS));
  }

  /**
   * {@link #needsIntercept(long)} for a span that routes only the id flags in {@code routedFlags}:
   * a span adds {@link KnownTagCodec#HAS_SCOPED_ALIAS} only once it holds a pending alias. One mask
   * test, as for the constant mask.
   */
  public boolean needsIntercept(long tagId, int routedFlags) {
    return ((int) tagId & routedFlags) != 0 || isSplitServiceTag(tagId);
  }

  public boolean needsIntercept(String tag) {
    long tagId = KnownTagCodec.keyOf(tag);
    return tagId != 0 ? needsIntercept(tagId) : isSplitServiceTag(tag);
  }

  public boolean interceptTag(DDSpanContext span, String tag, Object value) {
    long tagId = KnownTagCodec.keyOf(tag);
    return tagId != 0
        ? interceptResolvedTag(span, tagId, value)
        : interceptCustomTag(span, tag, value);
  }

  /**
   * Routes a known tag to a span field or a sampling directive. Returns true when the tag was
   * consumed, false when it should still be stored. The handler is picked by serial in two stages,
   * so a constant id inlines through to its handler and a varying id keeps the handler bodies out
   * of {@code DDSpanContext.setTag}'s compiled code; see {@link InterceptHandler}.
   */
  public boolean interceptTag(DDSpanContext span, long tagId, Object value) {
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      // Not a route: the tag is still stored. A write of it supersedes any pending alias.
      span.supersedeAliases(tagId);
    }
    return InterceptHandler.forSerial(KnownTagCodec.serialNum(tagId))
        .intercept(this, span, tagId, value);
  }

  /**
   * {@link #interceptTag(DDSpanContext, long, Object)} for an id resolved at run time, from a name
   * or an entry: nothing folds there, so it takes the handler from the table.
   */
  private boolean interceptResolvedTag(DDSpanContext span, long tagId, Object value) {
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      span.supersedeAliases(tagId);
    }
    return InterceptHandler.bySerial(KnownTagCodec.serialNum(tagId))
        .intercept(this, span, tagId, value);
  }

  /**
   * Routes an entry by its id, or by its name when it is a custom tag. Prechecks, so a primitive
   * entry that needs no interception is never boxed.
   */
  public boolean interceptTag(DDSpanContext span, TagMap.EntryReader entry) {
    long tagId = entry.tagId();
    if (tagId != 0) {
      return needsIntercept(tagId) && interceptResolvedTag(span, tagId, entry.objectValue());
    }
    return isSplitServiceTag(entry.tag()) && splitService(span, entry.objectValue());
  }

  /** Routes a custom tag -- one with no id. */
  public boolean interceptCustomTag(DDSpanContext span, String customTag, Object value) {
    return isSplitServiceTag(customTag) && splitService(span, value);
  }

  static boolean splitService(DDSpanContext span, Object value) {
    span.setServiceName(String.valueOf(value), SPLIT_BY_TAGS);
    return true;
  }

  static void setResourceFromUrl(
      @Nonnull final DDSpanContext span, @Nullable final String method, @Nonnull final Object url) {
    final String path;
    if (url instanceof URIUtils.LazyUrl) {
      path = ((URIUtils.LazyUrl) url).path();
    } else {
      URI uri = URIUtils.safeParse(url.toString());
      path = uri == null ? null : uri.getPath();
    }
    if (path != null) {
      final boolean isClient = Tags.SPAN_KIND_CLIENT.equals(span.getSpanKindString());
      Pair<CharSequence, Byte> normalized =
          isClient
              ? HttpResourceNames.computeForClient(method, path, false)
              : HttpResourceNames.computeForServer(method, path, false);
      if (normalized.hasLeft()) {
        span.setResourceName(normalized.getLeft(), normalized.getRight());
      }
    } else {
      span.setResourceName(
          HttpResourceNames.DEFAULT_RESOURCE_NAME, ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    }
  }

  /**
   * Re-applies the split-by-tags service name from the highest-precedence identity tag in a
   * decorator prototype that was just applied to {@code span}. The prototype's tags are intercepted
   * in no particular order, so without this {@code language} could overwrite {@code component}. The
   * precedence ({@code component} over {@code language}) mirrors the old sequential {@code setTag}
   * calls in {@code ServerDecorator}/{@code BaseDecorator}. It is scoped to the one application:
   * separate tag writes, before or after, stay last-writer-wins. ({@code span.kind} is not listed:
   * its interception never reaches the split-by-tags check.)
   */
  public void resolveSplitByTagsPrecedence(DDSpanContext span, TagMap prototypeTags) {
    if (isSplitServiceTag(COMPONENT_ID)) {
      TagMap.Entry component = prototypeTags.getEntry(COMPONENT_ID);
      if (component != null) {
        splitService(span, component.objectValue());
        return;
      }
    }
    if (isSplitServiceTag(LANGUAGE_ID)) {
      TagMap.Entry language = prototypeTags.getEntry(LANGUAGE_ID);
      if (language != null) {
        splitService(span, language.objectValue());
      }
    }
  }

  boolean interceptServiceName(RuleFlags.Feature feature, DDSpanContext span, Object value) {
    if (ruleFlags.isEnabled(feature)) {
      String serviceName = String.valueOf(value);
      span.setServiceName(serviceName);
      ServiceNameCollector.get().addService(serviceName);
      return true;
    }
    return false;
  }

  boolean interceptServletContext(DDSpanContext span, Object value) {
    // even though this tag is sometimes used to set the service name
    // (which has the side effect of marking the span as eligible for metrics
    // in the trace agent) we also want to store it in the tags no matter what,
    // so will always return false here.
    if (!splitByServletContext
        && (isServiceNameSetByUser
            || jeeSplitByDeployment
            || !ruleFlags.isEnabled(RuleFlags.Feature.SERVLET_CONTEXT)
            || !span.getServiceName().isEmpty()
                && !span.getServiceName().equals(inferredServiceName)
                && !span.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME))) {
      return false;
    }
    String contextName = String.valueOf(value).trim();
    if (!contextName.isEmpty()) {
      String serviceName = null;
      if (contextName.equals("/")) {
        serviceName = Config.get().getRootContextServiceName();
        span.setServiceName(serviceName, SPLIT_BY_SERVLET_CONTEXT);
      } else if (contextName.charAt(0) == '/') {
        if (contextName.length() > 1) {
          serviceName = contextName.substring(1);
          span.setServiceName(serviceName, SPLIT_BY_SERVLET_CONTEXT);
        }
      } else {
        serviceName = contextName;
        span.setServiceName(serviceName, SPLIT_BY_SERVLET_CONTEXT);
      }
      ServiceNameCollector.get().addService(serviceName);
    }
    return false;
  }

  static boolean asBoolean(Object value) {
    return Boolean.TRUE.equals(value)
        || "1".equals(value)
        || (!Boolean.FALSE.equals(value) && Boolean.parseBoolean(String.valueOf(value)));
  }

  static Number getOrTryParse(Object value) {
    if (value instanceof Number) {
      return (Number) value;
    } else if (value instanceof String) {
      try {
        return Double.parseDouble((String) value);
      } catch (NumberFormatException ignore) {

      }
    }
    return null;
  }
}
