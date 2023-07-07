/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package datadog.trace.instrumentation.thrift;

import java.util.Map;
import org.apache.thrift.AsyncProcessFunction;

public class AsyncContext extends AbstractContext {
    private final Map<String, AsyncProcessFunction> processMapView;

    public AsyncContext(Map<String, AsyncProcessFunction> processMapView) {
        this.processMapView = processMapView;
    }

    @Override
    public String getArguments() {
//      for (Map.Entry<String,AsyncProcessFunction> entry : processMapView.entrySet()){
//        System.out.println("ARGS1:"+entry.getKey()+"\t"+entry.getValue().getClass().getName());
//
//        System.out.println("ARGS2:"+entry.getValue().getEmptyArgsInstance().toString());
//      }
      if (processMapView==null){
        return null;
      }
      AsyncProcessFunction function = processMapView.get(methodName);
      if (function==null){
        return null;
      }
      return function.getEmptyArgsInstance().toString();
    }

    @Override
    public String getOperatorName() {
      if (processMapView==null){
        return null;
      }
      AsyncProcessFunction function = processMapView.get(methodName);
      if (function==null){
        return null;
      }
      return function.getClass().getName();
    }

}
