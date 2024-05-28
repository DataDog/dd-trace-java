package datadog.trace.agent.test.asserts

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.regex.Pattern

class TagsAssert {
  private final long spanParentId
  private final Map<String, Object> tags
  private final Set<String> assertedTags = new TreeSet<>()

  private TagsAssert(DDSpan span) {
    this.spanParentId = span.parentId
    this.tags = span.tags
  }

  static void assertTags(DDSpan span,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TagsAssert'])
    @DelegatesTo(value = TagsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def asserter = new TagsAssert(span)
    def clone = (Closure) spec.clone()
    clone.delegate = asserter
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(asserter)
    asserter.assertTagsAllVerified()
  }

  /**
   * Check that, if the peer.service tag source has been set, it matches the provided one.
   * @param sourceTag the source to match
   */
  def peerServiceFrom(String sourceTag) {
    tag(DDTags.PEER_SERVICE_SOURCE, { SpanNaming.instance().namingSchema().peerService().supports() ? it == sourceTag : it == null})
  }

  def defaultTagsNoPeerService(boolean distributedRootSpan = false) {
    defaultTags(distributedRootSpan, false)
  }

  /**
   * @param distributedRootSpan set to true if current span has a parent span but still considered 'root' for current service
   */
  def defaultTags(boolean distributedRootSpan = false, boolean checkPeerService = true) {
    assertedTags.add("thread.name")
    assertedTags.add("thread.id")
    assertedTags.add(DDTags.RUNTIME_ID_TAG)
    assertedTags.add(DDTags.LANGUAGE_TAG_KEY)
    assertedTags.add(RateByServiceTraceSampler.SAMPLING_AGENT_RATE)
    assertedTags.add(TraceMapper.SAMPLING_PRIORITY_KEY.toString())
    assertedTags.add("_sample_rate")
    assertedTags.add(DDTags.PID_TAG)
    assertedTags.add(DDTags.SCHEMA_VERSION_TAG_KEY)
    assertedTags.add(DDTags.PROFILING_ENABLED)
    assertedTags.add(DDTags.PROFILING_CONTEXT_ENGINE)
    assertedTags.add(DDTags.BASE_SERVICE)
    assertedTags.add(DDTags.DSM_ENABLED)
    assertedTags.add(DDTags.DJM_ENABLED)

    assert tags["thread.name"] != null
    assert tags["thread.id"] != null

    // FIXME: DQH - Too much conditional logic?  Maybe create specialized methods for client & server cases

    boolean isRoot = (DDSpanId.ZERO == spanParentId)
    if (isRoot) {
      assert tags[DDTags.SCHEMA_VERSION_TAG_KEY] == SpanNaming.instance().version()
    }
    if (isRoot || distributedRootSpan) {
      // If runtime id is actually different here, it might indicate that
      // the Config class was loaded on multiple different class loaders.
      assert tags[DDTags.RUNTIME_ID_TAG] == Config.get().runtimeId
    } else {
      assert tags[DDTags.RUNTIME_ID_TAG] == null
    }
    String spanKind = tags[Tags.SPAN_KIND]
    boolean isServer = (spanKind == Tags.SPAN_KIND_SERVER)
    if (isRoot || distributedRootSpan || isServer) {
      assert tags[DDTags.LANGUAGE_TAG_KEY] == DDTags.LANGUAGE_TAG_VALUE
    } else {
      assert tags[DDTags.LANGUAGE_TAG_KEY] == null
    }
    boolean shouldSetPeerService = checkPeerService && (spanKind == Tags.SPAN_KIND_CLIENT || spanKind == Tags.SPAN_KIND_PRODUCER)
    if (shouldSetPeerService && SpanNaming.instance().namingSchema().peerService().supports()) {
      assertedTags.add(Tags.PEER_SERVICE)
      assertedTags.add(DDTags.PEER_SERVICE_SOURCE)
      assert tags[Tags.PEER_SERVICE] != null
      assert tags[Tags.PEER_SERVICE] == tags[tags[DDTags.PEER_SERVICE_SOURCE]]
    } else {
      assert tags[Tags.PEER_SERVICE] == null
      assert tags[DDTags.PEER_SERVICE_SOURCE] == null
    }
  }

  def errorTags(Throwable error) {
    errorTags(error.getClass(), error.getMessage())
  }

  def errorTags(Class<Throwable> errorType) {
    errorTags(errorType, null)
  }

  def errorTags(Class<Throwable> errorType, message) {
    tag("error.type", {
      if (it == errorType.name) {
        return true
      }
      try {
        // also accept type names which are sub-classes of the given error type
        return errorType.isAssignableFrom(
          Class.forName(it as String, false, getClass().getClassLoader()))
      } catch (Throwable ignore) {
        return false
      }
    })
    tag("error.stack", String)

    if (message != null) {
      tag("error.message", message)
    }
  }

  def tag(String name, expected) {
    if (expected == null) {
      return
    }
    assertedTags.add(name)
    def value = tag(name)
    if (expected instanceof Pattern) {
      assert value =~ expected: "Tag \"$name\": \"${value.toString()}\" does not match pattern \"$expected\""
    } else if (expected instanceof Class) {
      assert ((Class) expected).isInstance(value): "Tag \"$name\": instance check $expected failed for \"${value.toString()}\" of class \"${value.class}\""
    } else if (expected instanceof Closure) {
      assert ((Closure) expected).call(value): "Tag \"$name\": closure call ${expected.toString()} failed with \"$value\""
    } else if (expected instanceof CharSequence) {
      assert value == expected.toString(): "Tag \"$name\": \"$value\" != \"${expected.toString()}\""
    } else {
      assert value == expected: "Tag \"$name\": \"$value\" != \"$expected\""
    }
  }

  def tag(String name) {
    def t = tags[name]
    return (t instanceof CharSequence) ? t.toString() : t
  }

  def methodMissing(String name, args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(args.toString())
    }
    tag(name, args[0])
  }

  def addTags(Map<String, Serializable> tags) {
    tags.each { tag(it.key, it.value) }
    true
  }

  void assertTagsAllVerified() {
    def set = new TreeMap<>(tags).keySet()
    set.removeAll(assertedTags)
    // The primary goal is to ensure the set is empty.
    // tags and assertedTags are included via an "always true" comparison
    // so they provide better context in the error message.
    assert tags.entrySet() != assertedTags && set.isEmpty()
  }
}
