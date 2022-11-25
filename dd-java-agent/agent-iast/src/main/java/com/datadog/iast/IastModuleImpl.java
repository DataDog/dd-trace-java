package com.datadog.iast;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static java.util.Arrays.asList;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.SourceType;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.model.VulnerabilityType.InjectionType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.Ranges.RangesProvider;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie;
import datadog.trace.util.stacktrace.StackWalker;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IastModuleImpl implements IastModule {

  private static final Logger LOG = LoggerFactory.getLogger(IastModuleImpl.class);

  private static final int NULL_STR_LENGTH = "null".length();

  private final Config config;
  private final Reporter reporter;
  private final OverheadController overheadController;
  private final StackWalker stackWalker = StackWalkerFactory.INSTANCE;

  public IastModuleImpl(
      final Config config, final Reporter reporter, final OverheadController overheadController) {
    this.config = config;
    this.reporter = reporter;
    this.overheadController = overheadController;
  }

  @Override
  public void onCipherAlgorithm(@Nullable final String algorithm) {
    if (algorithm == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakCipherAlgorithms().matcher(algorithmId).matches()) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    // get StackTraceElement for the callee of MessageDigest
    StackTraceElement stackTraceElement =
        stackWalker.walk(IastModuleImpl::findValidPackageForVulnerability);

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_CIPHER,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(algorithm));
    reporter.report(span, vulnerability);
  }

  @Override
  public void onHashingAlgorithm(@Nullable final String algorithm) {
    if (algorithm == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakHashAlgorithms().contains(algorithmId)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    // get StackTraceElement for the caller of MessageDigest
    StackTraceElement stackTraceElement =
        stackWalker.walk(IastModuleImpl::findValidPackageForVulnerability);

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_HASH,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(algorithm));
    reporter.report(span, vulnerability);
  }

  @Override
  public void onParameterName(@Nullable final String paramName) {
    if (paramName == null || paramName.isEmpty()) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        paramName, new Source(SourceType.REQUEST_PARAMETER_NAME, paramName, null));
  }

  @Override
  public void onParameterValue(
      @Nullable final String paramName, @Nullable final String paramValue) {
    if (paramValue == null || paramValue.isEmpty()) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        paramValue, new Source(SourceType.REQUEST_PARAMETER_VALUE, paramName, paramValue));
  }

  @Override
  public void onHeaderName(@Nullable final String headerName) {
    if (canBeTaintedNullSafe(headerName)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        headerName, new Source(SourceType.REQUEST_HEADER_NAME, headerName, null));
  }

  @Override
  public void onHeaderValue(@Nullable final String headerName, @Nullable final String headerValue) {
    if (canBeTaintedNullSafe(headerValue)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        headerValue, new Source(SourceType.REQUEST_HEADER_VALUE, headerName, headerValue));
  }

  @Override
  public void onStringConcat(
      @Nonnull final String left, @Nullable final String right, @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    if (!canBeTainted(left) && !canBeTaintedNullSafe(right)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject taintedLeft = getTainted(taintedObjects, left);
    final TaintedObject taintedRight = getTainted(taintedObjects, right);
    if (taintedLeft == null && taintedRight == null) {
      return;
    }
    final Range[] ranges;
    if (taintedRight == null) {
      ranges = taintedLeft.getRanges();
    } else if (taintedLeft == null) {
      ranges = new Range[taintedRight.getRanges().length];
      Ranges.copyShift(taintedRight.getRanges(), ranges, 0, left.length());
    } else {
      ranges = mergeRanges(left.length(), taintedLeft.getRanges(), taintedRight.getRanges());
    }
    taintedObjects.taint(result, ranges);
  }

  @Override
  public void onStringBuilderInit(
      @Nonnull final StringBuilder builder, @Nullable final CharSequence param) {
    if (!canBeTaintedNullSafe(param)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject paramTainted = taintedObjects.get(param);
    if (paramTainted == null) {
      return;
    }
    taintedObjects.taint(builder, paramTainted.getRanges());
  }

  @Override
  @SuppressFBWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
  public void onStringSubSequence(
      @Nullable String self, int beginIndex, int endIndex, @Nullable CharSequence result) {
    if (!canBeTaintedNullSafe(self) || !canBeTaintedNullSafe(result) || self == result) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject selfTainted = taintedObjects.get(self);
    if (selfTainted == null) {
      return;
    }
    final Range[] rangesSelf = selfTainted.getRanges();
    if (rangesSelf.length == 0) {
      return;
    }
    Range[] newRanges = Ranges.forSubstring(beginIndex, result.length(), rangesSelf);
    if (newRanges != null && newRanges.length > 0) {
      taintedObjects.taint(result, newRanges);
    }
  }

  @Override
  public void onStringBuilderAppend(
      @Nonnull final StringBuilder builder, @Nullable final CharSequence param) {
    if (!canBeTainted(builder) || !canBeTaintedNullSafe(param)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject paramTainted = taintedObjects.get(param);
    if (paramTainted == null) {
      return;
    }
    final TaintedObject builderTainted = taintedObjects.get(builder);
    final int shift = builder.length() - param.length();
    if (builderTainted == null) {
      final Range[] paramRanges = paramTainted.getRanges();
      final Range[] ranges = new Range[paramRanges.length];
      Ranges.copyShift(paramRanges, ranges, 0, shift);
      taintedObjects.taint(builder, ranges);
    } else {
      final Range[] builderRanges = builderTainted.getRanges();
      final Range[] paramRanges = paramTainted.getRanges();
      final Range[] ranges = mergeRanges(shift, builderRanges, paramRanges);
      builderTainted.setRanges(ranges);
    }
  }

  @Override
  public void onStringBuilderToString(
      @Nonnull final StringBuilder builder, @Nonnull final String result) {
    if (!canBeTainted(builder) || !canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject to = taintedObjects.get(builder);
    if (to == null) {
      return;
    }
    taintedObjects.taint(result, to.getRanges());
  }

  @Override
  public void onStringConcatFactory(
      @Nullable final String result,
      @Nullable final String[] args,
      @Nullable final String recipe,
      @Nullable final Object[] constants,
      @Nonnull final int[] recipeOffsets) {
    if (!canBeTaintedNullSafe(result) || !canBeTaintedNullSafe(args)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }

    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final Map<Integer, Range[]> sourceRanges = new HashMap<>();
    int rangeCount = 0;
    for (int i = 0; i < args.length; i++) {
      final TaintedObject to = getTainted(taintedObjects, args[i]);
      if (to != null) {
        final Range[] ranges = to.getRanges();
        sourceRanges.put(i, ranges);
        rangeCount += ranges.length;
      }
    }
    if (rangeCount == 0) {
      return;
    }

    final Range[] targetRanges = new Range[rangeCount];
    int offset = 0, rangeIndex = 0;
    for (int item : recipeOffsets) {
      if (item < 0) {
        offset += (-item);
      } else {
        final String argument = args[item];
        final Range[] ranges = sourceRanges.get(item);
        if (ranges != null) {
          Ranges.copyShift(ranges, targetRanges, rangeIndex, offset);
          rangeIndex += ranges.length;
        }
        offset += getToStringLength(argument);
      }
    }
    taintedObjects.taint(result, targetRanges);
  }

  @Override
  public void onJdbcQuery(@Nonnull String queryString) {
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    TaintedObject taintedObject = ctx.getTaintedObjects().get(queryString);
    if (taintedObject == null) {
      return;
    }

    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }

    StackTraceElement stackTraceElement =
        stackWalker.walk(IastModuleImpl::findValidPackageForVulnerability);

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.SQL_INJECTION,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(queryString, taintedObject.getRanges()));
    reporter.report(span, vulnerability);
  }

  @Override
  public void onRuntimeExec(@Nonnull final String... cmdArray) {
    if (!canBeTainted(cmdArray)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<String> rangesProvider = rangesProviderFor(to, cmdArray);
    checkInjection(VulnerabilityType.COMMAND_INJECTION, rangesProvider);
  }

  @Override
  public void onProcessBuilderStart(@Nonnull final List<String> command) {
    if (!canBeTainted(command)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<String> rangesProvider = Ranges.rangesProviderFor(to, command);
    checkInjection(VulnerabilityType.COMMAND_INJECTION, rangesProvider);
  }

  @Override
  public void onPathTraversal(final @Nonnull String path) {
    if (!canBeTainted(path)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<String> rangesProvider = rangesProviderFor(to, path);
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, rangesProvider);
  }

  @Override
  public void onPathTraversal(final @Nullable String parent, final @Nonnull String child) {
    if (!canBeTaintedNullSafe(parent) && !canBeTainted(child)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<String> rangeProvider;
    if (parent == null) {
      rangeProvider = rangesProviderFor(to, child);
    } else {
      rangeProvider = rangesProviderFor(to, asList(parent, child));
    }
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, rangeProvider);
  }

  @Override
  public void onPathTraversal(final @Nonnull String first, final @Nonnull String[] more) {
    if (!canBeTainted(first) && !canBeTainted(more)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<String> rangeProvider;
    if (more.length == 0) {
      rangeProvider = rangesProviderFor(to, first);
    } else {
      final List<String> items = new ArrayList<>(more.length + 1);
      items.add(first);
      Collections.addAll(items, more);
      rangeProvider = rangesProviderFor(to, items);
    }
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, rangeProvider);
  }

  @Override
  public void onPathTraversal(final @Nonnull URI uri) {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, rangesProviderFor(to, uri));
  }

  @Override
  public void onPathTraversal(final @Nullable File parent, final @Nonnull String child) {
    if (!canBeTainted(child)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<?> rangeProvider;
    if (parent == null) {
      rangeProvider = rangesProviderFor(to, child);
    } else {
      rangeProvider = rangesProviderFor(to, asList(parent, child));
    }
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, rangeProvider);
  }

  @Override
  public void onStringToUpperCase(@Nonnull String self, @Nonnull String result) {
    onStringCaseChanged(self, result);
  }

  @Override
  public void onStringToLowerCase(@Nonnull String self, @Nonnull String result) {
    onStringCaseChanged(self, result);
  }

  public void onStringCaseChanged(@Nonnull String self, @Nonnull String result) {
    if (!canBeTainted(result)) {
      return;
    }
    if (self.equals(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject taintedSelf = taintedObjects.get(self);
    if (taintedSelf == null) {
      return;
    }
    final Range[] rangesSelf = taintedSelf.getRanges();
    if (null == rangesSelf || rangesSelf.length == 0) {
      return;
    }
    if (result.length() >= self.length()) {
      taintedObjects.taint(result, rangesSelf);
    } // Pathological case where the string's length actually becomes smaller
    else {
      int skippedRanges = 0;
      Range adjustedRange = null;
      for (int i = rangesSelf.length - 1; i >= 0; i--) {
        Range currentRange = rangesSelf[i];
        if (currentRange.getStart() >= result.length()) {
          skippedRanges++;
        } else if (currentRange.getStart() + currentRange.getStart() >= result.length()) {
          adjustedRange =
              new Range(
                  currentRange.getStart(),
                  result.length() - currentRange.getStart(),
                  currentRange.getSource());
        }
      }
      Range[] newRanges = new Range[rangesSelf.length - skippedRanges];
      for (int i = 0; i < newRanges.length; i++) {
        newRanges[i] = rangesSelf[i];
      }
      if (null != adjustedRange) {
        newRanges[newRanges.length - 1] = adjustedRange;
      }
      taintedObjects.taint(result, newRanges);
    }
  }

  @Override
  public void onDirContextSearch(String name, @Nonnull String filterExpr, Object[] filterArgs) {
    List<String> elements = null;
    if (canBeTaintedNullSafe(name)) {
      elements = new ArrayList<>();
      elements.add(name);
    }
    if (canBeTaintedNullSafe(filterExpr)) {
      elements = getElements(elements);
      elements.add(filterExpr);
    }
    if (filterArgs != null) {
      for (int i = 0; i < filterArgs.length; i++) {
        if (filterArgs[i] != null && filterArgs[i] instanceof String) {
          String stringArg = (String) filterArgs[i];
          if (stringArg.length() > 0) {
            elements = getElements(elements);
            elements.add(stringArg);
          }
        }
      }
    }
    if (elements.isEmpty()) {
      LOG.debug("there ara no elements that can be tainted");
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      LOG.debug("No IastRequestContext available");
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<String> rangesProvider = Ranges.rangesProviderFor(to, elements);
    checkInjection(VulnerabilityType.LDAP_INJECTION, rangesProvider);
  }

  private static List<String> getElements(List<String> elements) {
    if (elements == null) {
      elements = new ArrayList<>();
    }
    return elements;
  }

  @Override
  public void onURLDecoderDecode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final TaintedObject tainted = to.get(value);
    if (tainted != null) {
      // TODO this is not enough, the resulting ranges can change due to the decodin process
      to.taint(result, tainted.getRanges());
    }
  }

  private <E> void checkInjection(
      @Nonnull final InjectionType type, @Nonnull final RangesProvider<E> rangeProvider) {
    final int rangeCount = rangeProvider.rangeCount();
    if (rangeCount == 0) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final StringBuilder evidence = new StringBuilder();
    final Range[] targetRanges = new Range[rangeCount];
    int rangeIndex = 0;
    for (int i = 0; i < rangeProvider.size(); i++) {
      final E item = rangeProvider.value(i);
      if (item != null) {
        if (evidence.length() > 0) {
          evidence.append(type.evidenceSeparator());
        }
        final Range[] taintedRanges = rangeProvider.ranges(item);
        if (taintedRanges != null) {
          Ranges.copyShift(taintedRanges, targetRanges, rangeIndex, evidence.length());
          rangeIndex += taintedRanges.length;
        }
        evidence.append(item);
      }
    }

    StackTraceElement stackTraceElement =
        stackWalker.walk(IastModuleImpl::findValidPackageForVulnerability);

    reporter.report(
        span,
        new Vulnerability(
            type,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(evidence.toString(), targetRanges)));
  }

  static StackTraceElement findValidPackageForVulnerability(Stream<StackTraceElement> stream) {
    final StackTraceElement[] first = new StackTraceElement[1];
    return stream
        .filter(
            stack -> {
              if (first[0] == null) {
                first[0] = stack;
              }
              return IastExclusionTrie.apply(stack.getClassName()) < 1;
            })
        .findFirst()
        .orElse(first[0]);
  }

  private static TaintedObject getTainted(final TaintedObjects to, final Object value) {
    return value == null ? null : to.get(value);
  }

  private static boolean canBeTainted(@Nonnull final CharSequence s) {
    return s.length() > 0;
  }

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence s) {
    return s != null && canBeTainted(s);
  }

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence[] args) {
    if (args == null) {
      return false;
    }
    return canBeTainted(args);
  }

  private static boolean canBeTainted(@Nonnull final CharSequence[] args) {
    if (args.length == 0) {
      return false;
    }
    for (final CharSequence item : args) {
      if (canBeTaintedNullSafe(item)) {
        return true;
      }
    }
    return false;
  }

  private static boolean canBeTainted(@Nonnull final List<? extends CharSequence> items) {
    if (items.size() == 0) {
      return false;
    }
    for (final CharSequence item : items) {
      if (canBeTaintedNullSafe(item)) {
        return true;
      }
    }
    return false;
  }

  private static int getToStringLength(@Nullable final String s) {
    return s == null ? NULL_STR_LENGTH : s.length();
  }

  private static Range[] mergeRanges(
      final int offset, @Nonnull final Range[] rangesLeft, @Nonnull final Range[] rangesRight) {
    final int nRanges = rangesLeft.length + rangesRight.length;
    final Range[] ranges = new Range[nRanges];
    if (rangesLeft.length > 0) {
      System.arraycopy(rangesLeft, 0, ranges, 0, rangesLeft.length);
    }
    if (rangesRight.length > 0) {
      Ranges.copyShift(rangesRight, ranges, rangesLeft.length, offset);
    }
    return ranges;
  }

  @Override
  public void onStringTrim(@Nonnull final String self, @Nonnull final String result) {
    // checks
    if (!canBeTainted(result)) {
      return;
    }
    if (self.equals(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject taintedSelf = taintedObjects.get(self);
    if (taintedSelf == null) {
      return;
    }

    int offset = 0;
    while ((offset < self.length()) && (self.charAt(offset) <= ' ')) {
      offset++;
    }

    int resultLength = result.length();

    final Range[] rangesSelf = taintedSelf.getRanges();
    if (null == rangesSelf || rangesSelf.length == 0) {
      return;
    }

    final Range[] newRanges = Ranges.forSubstring(offset, resultLength, rangesSelf);

    if (null != newRanges) {
      taintedObjects.taint(result, newRanges);
    }
  }
}
