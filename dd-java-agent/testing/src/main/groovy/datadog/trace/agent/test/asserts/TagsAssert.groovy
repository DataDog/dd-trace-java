package datadog.trace.agent.test.asserts

import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.regex.Pattern

class TagsAssert {
  private final DDId spanParentId
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
   * @param distributedRootSpan set to true if current span has a parent span but still considered 'root' for current service
   */
  def defaultTags(boolean distributedRootSpan = false) {
    assertedTags.add("thread.name")
    assertedTags.add("thread.id")
    assertedTags.add(DDTags.RUNTIME_ID_TAG)
    assertedTags.add(DDTags.LANGUAGE_TAG_KEY)

    assert tags["thread.name"] != null
    assert tags["thread.id"] != null

    // FIXME: DQH - Too much conditional logic?  Maybe create specialized methods for client & server cases

    boolean isRoot = (DDId.ZERO == spanParentId)
    if (isRoot || distributedRootSpan) {
      assert tags[DDTags.RUNTIME_ID_TAG] == Config.get().runtimeId
    } else {
      assert tags[DDTags.RUNTIME_ID_TAG] == null
    }

    boolean isServer = (tags[Tags.SPAN_KIND] == Tags.SPAN_KIND_SERVER)
    if (isRoot || distributedRootSpan || isServer) {
      assert tags[DDTags.LANGUAGE_TAG_KEY] == DDTags.LANGUAGE_TAG_VALUE
    } else {
      assert tags[DDTags.LANGUAGE_TAG_KEY] == null
    }
  }

  def errorTags(Class<Throwable> errorType) {
    errorTags(errorType, null)
  }

  def errorTags(Class<Throwable> errorType, message) {
    tag("error.type", errorType.name)
    tag("error.stack", String)

    if (message != null) {
      tag("error.msg", message)
    }
  }

  def tag(String name, value) {
    if (value == null) {
      return
    }
    assertedTags.add(name)
    def t = tag(name)
    if (value instanceof Pattern) {
      assert t =~ value
    } else if (value instanceof Class) {
      assert ((Class) value).isInstance(t)
    } else if (value instanceof Closure) {
      assert ((Closure) value).call(t)
    } else {
      assert t == value
    }
  }

  def tag(String name) {
    def t = tags[name]
    return (t instanceof UTF8BytesString) ? t.toString() : t
  }

  def methodMissing(String name, args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(args.toString())
    }
    tag(name, args[0])
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
