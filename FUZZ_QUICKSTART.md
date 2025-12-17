# DD-Trace-Java Configuration Fuzzer - Quick Start Guide

## What Was Created

This fuzzing toolset helps you test your dd-trace-java application with randomized configurations to identify potential issues.

### Files Created

1. **`fuzz-configs.sh`** - Main fuzzer script
   - Generates random but sensible configuration values
   - Runs your app with different parameter combinations
   - Logs all runs with full details

2. **`analyze-fuzz-logs.sh`** - Log analyzer
   - Analyzes fuzzing results
   - Identifies failure patterns
   - Provides statistics and recommendations

3. **`example-fuzz.sh`** - Quick start example
   - Demonstrates basic usage
   - Checks prerequisites
   - Runs a simple test

4. **`FUZZ_README.md`** - Comprehensive documentation
   - Detailed usage instructions
   - Parameter type detection
   - Troubleshooting guide

## Quick Start

### 1. Prerequisites

Install `jq` (JSON processor):

```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt-get install jq
```

### 2. Run Your First Test

```bash
# Simple test (5 iterations with echo command)
./example-fuzz.sh

# Or directly with your Java app:
./fuzz-configs.sh 10 "java -javaagent:dd-java-agent.jar -jar myapp.jar"
```

### 3. Analyze Results

```bash
./analyze-fuzz-logs.sh
```

## Usage Examples

### Basic Testing
```bash
# Run 10 iterations
./fuzz-configs.sh 10 "java -jar app.jar"
```

### With DD Agent
```bash
# Test with the datadog agent jar
./fuzz-configs.sh 20 "java -javaagent:./dd-java-agent/build/libs/dd-java-agent.jar -jar myapp.jar"
```

### Spring Boot Application
```bash
# Test Spring Boot app
./fuzz-configs.sh 15 "java -javaagent:dd-java-agent.jar -jar target/spring-app-1.0.0.jar"
```

### Custom Script
```bash
# Test with your startup script
./fuzz-configs.sh 30 "./start-my-app.sh"
```

## What the Fuzzer Does

For each iteration, it:

1. **Selects** 1-10 random configuration parameters (from 1384+ available)
2. **Generates** appropriate values based on parameter type:
   - Booleans: `true`, `false`, `1`, `0`
   - Ports: `1024-65535`
   - Timeouts: `100-30000ms`
   - Sample rates: `0.0-1.0`
   - Strings: Realistic values (URLs, paths, service names, etc.)
3. **Runs** your application with those settings
4. **Logs** everything (config + output)
5. **Reports** success/failure/timeout

## Understanding Output

### During Run
```
Iteration 5 of 10
==================================================================
Selected 7 random parameters:
  DD_TRACE_ENABLED = true
  DD_SERVICE = my-service
  DD_ENV = production
  DD_AGENT_PORT = 8126
  DD_TRACE_SAMPLE_RATE = 0.75
  DD_PROFILING_ENABLED = true
  DD_LOGS_INJECTION = false

Running application...
✓ Iteration 5 completed successfully
```

### Summary
```
Fuzzing Complete - Summary
==================================================================
Total iterations:    10
Successful runs:     9
Failed runs:         1
Timeout runs:        0
Logs directory:      ./fuzz-logs
```

## Analyzing Logs

### Check Individual Runs
```bash
# View a specific log
cat fuzz-logs/fuzz_run_5_20241128_143052.log

# Find failed runs
grep -l "exit code" fuzz-logs/*.log
```

### Use the Analyzer
```bash
./analyze-fuzz-logs.sh
```

This shows:
- Success/failure statistics
- Most frequently used parameters
- Recent runs summary
- Recommendations

## Configuration Types Detected

The fuzzer intelligently detects parameter types:

| Parameter Pattern | Generated Values | Examples |
|------------------|------------------|----------|
| `*_ENABLED`, `*_DEBUG` | Boolean | `true`, `false`, `1`, `0` |
| `*_PORT` | Port number | `1024-65535` |
| `*_TIMEOUT`, `*_DELAY` | Milliseconds | `100-30000` |
| `*_SAMPLE_RATE` | Float | `0.0-1.0` |
| `DD_ENV` | Environment | `production`, `staging`, `development` |
| `DD_SERVICE` | Service name | `my-service`, `web-app`, `api-gateway` |
| `*_HOST*` | Hostname | `localhost`, `127.0.0.1`, IPs |
| `*_URL`, `*_ENDPOINT` | URL | `http://localhost:8080`, etc. |
| `*_PATH`, `*_FILE` | Path | `/tmp/test`, `/var/log/app` |
| `*_KEY`, `*_TOKEN` | Hex string | Random hex |
| `*_TAGS` | Tag list | `key1:value1,key2:value2` |

## Tips for Effective Fuzzing

1. **Start Small**: Begin with 5-10 iterations to verify setup
2. **Increase Gradually**: Scale up to 50-100 iterations for thorough testing
3. **Monitor**: Watch app logs and metrics during fuzzing
4. **Analyze Failures**: Use `analyze-fuzz-logs.sh` to identify patterns
5. **CI/CD Integration**: Run fuzzing in your pipeline
6. **Long-Running**: Consider overnight fuzz runs with 1000+ iterations

## Common Issues

### "jq: command not found"
Install jq using your package manager (see Prerequisites)

### All runs timeout
- Increase timeout in `fuzz-configs.sh` (search for `timeout 30s`)
- Check if your app is starting correctly
- Verify your command is correct

### Permission denied
```bash
chmod +x fuzz-configs.sh analyze-fuzz-logs.sh example-fuzz.sh
```

### Want to test specific parameters
Edit `fuzz-configs.sh` and modify the parameter selection logic or create a focused test script

## Next Steps

1. ✅ Run `./example-fuzz.sh` to verify everything works
2. ✅ Test with your actual Java application
3. ✅ Analyze logs with `./analyze-fuzz-logs.sh`
4. ✅ Adjust parameters/iterations based on findings
5. ✅ Integrate into CI/CD pipeline
6. ✅ Document any configuration issues you discover

## Advanced Usage

### Parallel Testing
```bash
# Run multiple fuzzer instances
./fuzz-configs.sh 50 "java -jar app.jar" &
./fuzz-configs.sh 50 "java -jar app.jar" &
wait
```

### Custom Parameter Ranges
Edit `generate_integer()` or `generate_string()` functions in `fuzz-configs.sh`

### Integration Test Mode
```bash
# Run with health check
./fuzz-configs.sh 20 "java -jar app.jar && curl http://localhost:8080/health"
```

## Support

- See `FUZZ_README.md` for comprehensive documentation
- Check logs in `fuzz-logs/` for debugging
- Review dd-trace-java documentation at https://docs.datadoghq.com/tracing/trace_collection/library_config/java/

---

**Total Configurations Available**: 1384+ parameters from `metadata/supported-configurations.json`

**Fuzzer Version**: 1.0.0

