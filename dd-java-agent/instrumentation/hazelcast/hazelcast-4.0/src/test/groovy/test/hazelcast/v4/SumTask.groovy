package test.hazelcast.v4

import com.hazelcast.collection.IList
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.HazelcastInstanceAware
import java.util.concurrent.Callable

class SumTask
implements Callable<Integer>, Serializable, HazelcastInstanceAware {

  private transient HazelcastInstance hazelcastInstance

  void setHazelcastInstance( HazelcastInstance hazelcastInstance ) {
    this.hazelcastInstance = hazelcastInstance
  }

  Integer call() throws Exception {
    IList<Integer> list = hazelcastInstance.getList( "sum" )
    int result = 0
    for ( Integer value : list ) {
      result += value
    }
    return result
  }
}
