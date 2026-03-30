package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.core.CoreTracer.TraceInterceptors;

public class TraceInterceptorsTest {
  @Test
  public void empty() {
	TraceInterceptors interceptors = new TraceInterceptors();
	assertTrue(interceptors.isEmpty());
	
	TraceInterceptor[] interceptorsArray = interceptors.interceptors();
	assertEquals(0, interceptorsArray.length);
  }
  
  @Test
  public void addInOrder() {
	TestInterceptor first = new TestInterceptor(0);
	TestInterceptor second = new TestInterceptor(1);
	TestInterceptor third = new TestInterceptor(2);
	
	TraceInterceptors interceptors = new TraceInterceptors();
	assertNull(interceptors.add(first));
	assertNull(interceptors.add(second));
	assertNull(interceptors.add(third));
	
	assertFalse(interceptors.isEmpty());
	
	TraceInterceptor[] interceptorsArray = interceptors.interceptors();
	assertEquals(3, interceptorsArray.length);
	assertSame(first, interceptorsArray[0]);
	assertSame(second, interceptorsArray[1]);
	assertSame(third, interceptorsArray[2]);
  }
  
  @Test
  public void addReverseOrder() {
	TestInterceptor first = new TestInterceptor(0);
	TestInterceptor second = new TestInterceptor(1);
	TestInterceptor third = new TestInterceptor(2);
	
	TraceInterceptors interceptors = new TraceInterceptors();
	assertNull(interceptors.add(third));
	assertNull(interceptors.add(second));
	assertNull(interceptors.add(first));
	
	assertFalse(interceptors.isEmpty());
	
	TraceInterceptor[] interceptorsArray = interceptors.interceptors();
	assertEquals(3, interceptorsArray.length);
	assertSame(first, interceptorsArray[0]);
	assertSame(second, interceptorsArray[1]);
	assertSame(third, interceptorsArray[2]);	  
  }
  
  @Test
  public void addShuffle() {
	int SIZE = 10;
	List<TraceInterceptor> toAdd = new ArrayList<>(SIZE);
	
	for ( int i = 0; i < SIZE; ++i ) {
	  toAdd.add(new TestInterceptor(i));
	}
	
	List<TraceInterceptor> toAddShuffled = new ArrayList<>(toAdd);
	Collections.shuffle(toAddShuffled);
	
	TraceInterceptors interceptors = new TraceInterceptors();
	for ( TraceInterceptor interceptor: toAddShuffled ) {
	  assertNull(interceptors.add(interceptor));
	}
	
	assertFalse(interceptors.isEmpty());
	
	TraceInterceptor[] interceptorsArray = interceptors.interceptors();
	assertEquals(SIZE, interceptorsArray.length);
	
	for ( int i = 0; i < interceptorsArray.length; ++i ) {
	  TraceInterceptor expected = toAdd.get(i);
	  TraceInterceptor actual = interceptorsArray[i];
	  
	  assertSame(expected, actual);
	}
  }
  
  @Test
  public void addConflict() {
	TestInterceptor first = new TestInterceptor(0);
	TestInterceptor second = new TestInterceptor(1);
	TestInterceptor third = new TestInterceptor(2);
	
	TraceInterceptors interceptors = new TraceInterceptors();
	
	assertNull(interceptors.add(first));
	assertNull(interceptors.add(second));
	assertNull(interceptors.add(third));
	
	assertSame(first, interceptors.add(new TestInterceptor(0)));
	assertSame(second, interceptors.add(new TestInterceptor(1)));
	assertSame(third, interceptors.add(new TestInterceptor(2)));
  }
  
  static final class TestInterceptor implements TraceInterceptor {
	private final int priority;
	
	TestInterceptor(int priority) {
	  this.priority = priority;
	}
	
	@Override
	public Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
	  return trace;
	}

	@Override
	public int priority() {
	  return this.priority;
	}
	
	@Override
	public String toString() {
	  return "TestInterceptor(priority=" + this.priority + ")";
	}
  }
}
