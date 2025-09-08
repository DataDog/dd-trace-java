package com.datadog.iast.test

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObject
import com.datadog.iast.taint.TaintedObjects
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.Matcher

import java.util.regex.Pattern
import java.util.stream.Collectors
import java.util.stream.StreamSupport

import org.hamcrest.Matchers

import static org.hamcrest.Matchers.equalTo
import static org.hamcrest.Matchers.greaterThan
import static org.hamcrest.Matchers.matchesPattern
import static org.hamcrest.Matchers.nullValue

class TaintedObjectCollection {
  private final TaintedObjects taintedObjects // holds strong references to the tainted objects
  private final List<TaintedObject> coll

  TaintedObjectCollection(TaintedObjects tobjs) {
    this.taintedObjects = tobjs
    if (tobjs) {
      this.coll = StreamSupport.stream(tobjs.spliterator(), false).collect(Collectors.toList())
    } else {
      this.coll = []
    }
  }

  @Override
  String toString() {
    '[' + coll.collect { taintedObjectToStr(it) }.join(",\n") + ']'
  }

  static String taintedObjectToStr(TaintedObject to) {
    "TO{${to.get()}, ranges=${to.ranges}"
  }

  static class MatchingDelegate extends BaseMatcher<TaintedObject> {
    private Matcher valueMatcher
    List<RangeMatcher> ranges = []

    @CompileStatic
    void value(Object obj) {
      if (obj instanceof Matcher) {
        valueMatcher = obj
      } else if (obj instanceof Pattern) {
        valueMatcher = Matchers.matchesPattern(obj)
      } else {
        valueMatcher = Matchers.equalTo(obj)
      }
    }

    private static Matcher toMatcher(Object obj) {
      if (obj instanceof Matcher) {
        obj
      } else if (obj instanceof Pattern) {
        Matchers.matchesPattern(obj)
      } else if (obj == null) {
        Matchers.nullValue()
      } else {
        Matchers.equalTo(obj)
      }
    }

    SourceMatcher source(byte origin, Object name, Object value) {
      new SourceMatcher(origin, toMatcher(name), toMatcher(value))
    }

    SourceMatcher source(byte origin) {
      new SourceMatcher(origin, Matchers.anything(), Matchers.anything())
    }

    void range(int start, int length, SourceMatcher source) {
      ranges << new RangeMatcher(start, Matchers.equalTo(length), source)
    }

    void range(int start, Matcher<Integer> length, SourceMatcher source) {
      ranges << new RangeMatcher(start, length, source)
    }

    void range(SourceMatcher source) {
      ranges << new RangeMatcher(0, Matchers.greaterThan(0), source)
    }

    @Override
    boolean matches(Object obj) {
      if (!(obj instanceof TaintedObject)) {
        return false
      }
      TaintedObject tobj = obj
      valueMatcher.matches(tobj.get()) &&
        ranges.every { RangeMatcher rm ->
          tobj.ranges.any { range ->
            rm.matches(range)
          }
        }
    }

    @Override
    void describeTo(Description description) {
    }
  }

  @Canonical
  static class SourceMatcher extends BaseMatcher<Source> {
    byte origin
    Matcher<String> name
    Matcher<String> value

    @Override
    boolean matches(Object actual) {
      if (!(actual instanceof Source)) {
        return false
      }
      Source source = actual
      source.origin == origin && matchesName(source) && matchesValue(source)
    }

    private boolean matchesName(final Source source) {
      source.name == Source.GARBAGE_COLLECTED_REF || name.matches(source.name)
    }

    private boolean matchesValue(final Source source) {
      source.value == Source.GARBAGE_COLLECTED_REF || value.matches(source.value)
    }

    @Override
    void describeTo(Description description) {
      description.appendText('a Source with name matching ')
      name.describeTo(description)
      description.appendText(', origin ')
        .appendValue(origin)
      description.appendText(', and value matching ')
      value.describeTo(description)
    }
  }

  @Canonical
  static class RangeMatcher extends BaseMatcher<Range> {
    int start
    Matcher<Integer> length
    SourceMatcher sourceMatcher

    @Override
    boolean matches(Object actual) {
      if (!(actual instanceof Range)) {
        return false
      }
      Range range = actual
      range.start == start && length.matches(range.length) &&
        sourceMatcher.matches(range.source)
    }

    @Override
    void describeTo(Description description) {
      description.appendText('a Range starting at ')
        .appendValue(start)
        .appendText(', with length matching ')
      length.describeTo(description)
      description.appendText(', and source matching ')
      sourceMatcher.describeTo(description)
    }
  }

  boolean hasTaintedObject(@DelegatesTo(TaintedObjectCollection.MatchingDelegate) Closure predicate) {
    def matchDelegate = new TaintedObjectCollection.MatchingDelegate()
    predicate.delegate = matchDelegate
    predicate.call()

    coll.any { matchDelegate.matches(it) }
  }

  int size() {
    coll.size()
  }
}
