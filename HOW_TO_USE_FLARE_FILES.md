# How to Parse Tracer Flare Files

## 📦 Files

- `tracer_flare_current_state.txt` - Current Java tracer output
- `tracer_flare_ideal_state.txt` - Enhanced tracer output with all reproduction data

## 📋 File Format

Both files use key=value pairs organized in sections:

```
================================================================================
SECTION_NAME
================================================================================
key1=value1
key2=value2
```

## 🔍 Parsing Examples

### Extract Dependencies (Ideal State Only)

```python
def parse_dependencies(filepath):
    deps = {}
    in_section = False
    
    with open(filepath, 'r') as f:
        for line in f:
            if 'DEPENDENCIES' in line:
                in_section = True
                continue
            if line.startswith('==='):
                in_section = False
            if in_section and '=' in line:
                lib, version = line.strip().split('=', 1)
                if ':' in lib:  # Maven format
                    deps[lib] = version
    
    return deps

# Usage
deps = parse_dependencies('tracer_flare_ideal_state.txt')
print(deps['org.springframework.boot:spring-boot-starter-web'])  # 3.0.1
```

### Extract Load Profile (Ideal State Only)

```python
def parse_load_profile(filepath):
    load = {}
    in_section = False
    
    with open(filepath, 'r') as f:
        for line in f:
            if 'LOAD PROFILE' in line:
                in_section = True
                continue
            if line.startswith('==='):
                in_section = False
            if in_section and '=' in line:
                key, value = line.strip().split('=', 1)
                load[key] = value
    
    return load

# Usage
load = parse_load_profile('tracer_flare_ideal_state.txt')
print(f"RPS: {load['traces_per_second_5min']}")  # 456.8
print(f"Concurrent: {load['concurrent_requests']}")  # 45
```

### Extract Trace Statistics (Ideal State Only)

```python
def parse_trace_statistics(filepath):
    endpoints = []
    current_endpoint = None
    in_section = False
    
    with open(filepath, 'r') as f:
        for line in f:
            if 'TRACE STATISTICS' in line:
                in_section = True
                continue
            if line.startswith('==='):
                break
            if in_section and '=' in line:
                key, value = line.strip().split('=', 1)
                
                if key == 'endpoint':
                    if current_endpoint:
                        endpoints.append(current_endpoint)
                    current_endpoint = {'endpoint': value}
                elif current_endpoint:
                    current_endpoint[key] = value
        
        if current_endpoint:
            endpoints.append(current_endpoint)
    
    return endpoints

# Usage
endpoints = parse_trace_statistics('tracer_flare_ideal_state.txt')
for ep in endpoints:
    print(f"{ep['endpoint']}: p95={ep['p95_ms']}ms")
```

### Extract Slow Traces (Ideal State Only)

```python
def parse_slow_traces(filepath):
    traces = []
    current_trace = None
    current_spans = []
    in_section = False
    
    with open(filepath, 'r') as f:
        for line in f:
            if 'SLOW TRACES' in line:
                in_section = True
                continue
            if line.startswith('==='):
                break
            if in_section and '=' in line:
                key, value = line.strip().split('=', 1)
                
                if key == 'trace_id':
                    if current_trace:
                        current_trace['spans'] = current_spans
                        traces.append(current_trace)
                    current_trace = {'trace_id': value}
                    current_spans = []
                elif key.startswith('span_id'):
                    # Parse span: span_id=1,parent_id=0,operation=servlet.request,duration_ms=8542
                    span_parts = dict(part.split('=', 1) for part in value.split(','))
                    current_spans.append(span_parts)
                elif current_trace:
                    current_trace[key] = value
        
        if current_trace:
            current_trace['spans'] = current_spans
            traces.append(current_trace)
    
    return traces

# Usage
traces = parse_slow_traces('tracer_flare_ideal_state.txt')
for trace in traces:
    print(f"Trace {trace['trace_id']}: {trace['duration_ms']}ms")
    for span in trace['spans']:
        print(f"  - {span['operation']}: {span['duration_ms']}ms")
```

### Extract Benchmark Inputs (Ideal State Only)

```python
def parse_benchmark_inputs(filepath):
    inputs = {
        'endpoint_weights': {},
        'load': {},
        'performance_targets': {}
    }
    in_section = False
    
    with open(filepath, 'r') as f:
        for line in f:
            if 'BENCHMARK INPUTS' in line:
                in_section = True
                continue
            if in_section and '=' in line:
                key, value = line.strip().split('=', 1)
                
                if key.startswith('endpoint_weight'):
                    endpoint, weight = value.rsplit(',', 1)
                    inputs['endpoint_weights'][endpoint] = int(weight)
                elif key.startswith('load_'):
                    inputs['load'][key.replace('load_', '')] = value
                elif key.startswith('performance_target_'):
                    inputs['performance_targets'][key.replace('performance_target_', '')] = value
    
    return inputs

# Usage
inputs = parse_benchmark_inputs('tracer_flare_ideal_state.txt')
print(f"Peak RPS: {inputs['load']['peak_rps']}")  # 550
print(f"Checkout weight: {inputs['endpoint_weights']['POST /api/v1/checkout']}%")  # 40
```

## 🎯 Generate Vegeta Test

```bash
#!/bin/bash

# Extract values from ideal state file
RPS=$(grep "^load_peak_rps=" tracer_flare_ideal_state.txt | cut -d'=' -f2)
DURATION=$(grep "^load_test_duration_seconds=" tracer_flare_ideal_state.txt | cut -d'=' -f2)
WORKERS=$(grep "^load_concurrent_users=" tracer_flare_ideal_state.txt | cut -d'=' -f2)

# Create targets file
cat > targets.txt << EOF
POST http://localhost:8080/api/v1/checkout
Content-Type: application/json

GET http://localhost:8080/api/v1/cart/123

GET http://localhost:8080/api/v1/inventory/check?sku=SKU001

POST http://localhost:8080/api/v1/order/create
Content-Type: application/json
EOF

# Run test
vegeta attack -rate=${RPS}/1s -duration=${DURATION}s -workers=${WORKERS} \
  < targets.txt | vegeta report
```

## 🎪 Generate JMeter Test

```python
import xml.etree.ElementTree as ET

def generate_jmeter_test(flare_file, output_file):
    # Parse inputs
    inputs = parse_benchmark_inputs(flare_file)
    
    # Create JMeter XML
    testplan = ET.Element('jmeterTestPlan')
    
    # Add thread group
    threadgroup = ET.SubElement(testplan, 'ThreadGroup')
    ET.SubElement(threadgroup, 'stringProp', name='ThreadGroup.num_threads').text = \
        inputs['load']['concurrent_users']
    ET.SubElement(threadgroup, 'stringProp', name='ThreadGroup.ramp_time').text = \
        inputs['load']['ramp_duration_seconds']
    ET.SubElement(threadgroup, 'stringProp', name='ThreadGroup.duration').text = \
        inputs['load']['test_duration_seconds']
    
    # Add HTTP samplers for each endpoint
    for endpoint, weight in inputs['endpoint_weights'].items():
        # Add sampler...
        pass
    
    # Write XML
    tree = ET.ElementTree(testplan)
    tree.write(output_file)

# Usage
generate_jmeter_test('tracer_flare_ideal_state.txt', 'test.jmx')
```

## 🚀 Generate k6 Test

```python
def generate_k6_test(flare_file, output_file):
    inputs = parse_benchmark_inputs(flare_file)
    
    k6_script = f"""
import http from 'k6/http';
import {{ check }} from 'k6';

export let options = {{
  stages: [
    {{ duration: '{inputs['load']['ramp_duration_seconds']}s', target: {inputs['load']['concurrent_users']} }},
    {{ duration: '{inputs['load']['peak_duration_seconds']}s', target: {inputs['load']['concurrent_users']} }},
  ],
}};

export default function() {{
  // Weighted endpoint selection
  const rand = Math.random() * 100;
  
  if (rand < {inputs['endpoint_weights']['POST /api/v1/checkout']}) {{
    let res = http.post('http://localhost:8080/api/v1/checkout');
    check(res, {{ 'checkout status 200': (r) => r.status === 200 }});
  }} else if (rand < {inputs['endpoint_weights']['POST /api/v1/checkout'] + inputs['endpoint_weights']['GET /api/v1/cart/{{id}}']}) {{
    let res = http.get('http://localhost:8080/api/v1/cart/123');
    check(res, {{ 'cart status 200': (r) => r.status === 200 }});
  }}
  // Add more endpoints...
}}
"""
    
    with open(output_file, 'w') as f:
        f.write(k6_script)

# Usage
generate_k6_test('tracer_flare_ideal_state.txt', 'test.js')
```

## 📊 Key Sections

| Section | Current State | Ideal State | Purpose |
|---------|--------------|-------------|---------|
| METADATA | ✅ | ✅ | Basic info |
| RUNTIME | ✅ | ✅ | JVM config |
| DEPENDENCIES | ❌ | ✅ | Exact versions |
| INSTRUMENTATION STATE | ✅ | ✅ | What's instrumented |
| HEALTH METRICS | ✅ | ✅ | Counters |
| LOAD PROFILE | ❌ | ✅ | Request rates |
| TRACE STATISTICS | ❌ | ✅ | p50/p95/p99 |
| SLOW TRACES | ❌ | ✅ | Bottlenecks |
| BENCHMARK INPUTS | ❌ | ✅ | Test generation |

## 💡 Quick Reference

```python
# One-liner to get RPS
rps = [l.split('=')[1] for l in open('tracer_flare_ideal_state.txt') if 'traces_per_second_5min=' in l][0]

# One-liner to get all endpoints
endpoints = [l.split('=')[1].strip() for l in open('tracer_flare_ideal_state.txt') if l.startswith('endpoint=')]

# One-liner to get target p95
targets = {l.split('_')[2]: l.split('=')[1] for l in open('tracer_flare_ideal_state.txt') if 'p95_ms=' in l}
```
