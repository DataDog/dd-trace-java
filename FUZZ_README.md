# DD-Trace-Java Configuration Fuzzer

A bash script that performs fuzz testing on dd-trace-java by generating random but sensible configuration combinations.

## Overview

This fuzzer automatically:
- Reads all available configuration parameters from `metadata/supported-configurations.json`
- Generates intelligent random values based on parameter name patterns
- Runs your Java application with different configuration combinations
- Logs all runs with their configurations and outcomes
- Provides detailed statistics at the end

## Features

- **Intelligent Value Generation**: The fuzzer analyzes parameter names to generate appropriate values:
  - Boolean parameters (`ENABLED`, `DEBUG`, etc.) → `true`, `false`, `1`, `0`
  - Port numbers → `1024-65535`
  - Timeouts/delays → `100-30000ms`
  - Sample rates → `0.0-1.0`
  - URLs, paths, service names, tags, etc. with realistic values

- **Configurable Parameters Per Run**: Maximum 10 parameters per run (configurable)
- **Comprehensive Logging**: Each run is logged with full configuration and output
- **Timeout Protection**: 30-second timeout per run to prevent hangs
- **Statistics**: Summary of successful/failed/timeout runs

## Prerequisites

- Bash 4.0+
- `jq` (JSON processor)
- `timeout` command (usually pre-installed on Linux/macOS)

Install jq if needed:
```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt-get install jq

# CentOS/RHEL
sudo yum install jq
```

## Usage

### Basic Usage

```bash
./fuzz-configs.sh <iterations> "<java_command>"
```

### Examples

#### Example 1: Test with a simple Java application
```bash
./fuzz-configs.sh 10 "java -javaagent:dd-java-agent/build/libs/dd-java-agent.jar -jar myapp.jar"
```

#### Example 2: Test with Spring Boot application
```bash
./fuzz-configs.sh 20 "java -javaagent:./dd-java-agent.jar -jar target/spring-boot-app.jar"
```

#### Example 3: Test with a script that starts your app
```bash
./fuzz-configs.sh 50 "./start-app.sh"
```

#### Example 4: Just print configurations (testing mode)
```bash
./fuzz-configs.sh 5 "echo 'Testing configuration'"
```

#### Example 5: Run with custom JVM options
```bash
./fuzz-configs.sh 15 "java -Xmx2g -javaagent:dd-java-agent.jar -jar app.jar"
```

## Output

The fuzzer creates a `fuzz-logs` directory containing:
- Individual log files for each iteration
- Configuration used for each run
- Application output/errors
- Exit codes

### Sample Log File Content

```
# Fuzz Iteration 1
# Timestamp: 20241128_143052
# Configuration:
DD_TRACE_ENABLED=true
DD_SERVICE=my-service
DD_ENV=production
DD_AGENT_PORT=8126
DD_TRACE_SAMPLE_RATE=0.75

# Environment Exports:
export DD_TRACE_ENABLED='true'
export DD_SERVICE='my-service'
export DD_ENV='production'
export DD_AGENT_PORT='8126'
export DD_TRACE_SAMPLE_RATE='0.75'

# Command: java -jar myapp.jar
==========================================

[Application output here...]
```

## Configuration

You can modify these variables in the script:

```bash
MAX_PARAMS_PER_RUN=10    # Maximum parameters per iteration
LOG_DIR="./fuzz-logs"    # Log directory
```

## Parameter Type Detection

The fuzzer intelligently detects parameter types based on naming patterns:

| Pattern | Type | Example Values |
|---------|------|----------------|
| `*ENABLED`, `*DEBUG` | Boolean | `true`, `false`, `1`, `0` |
| `*PORT` | Integer | `1024-65535` |
| `*TIMEOUT`, `*DELAY` | Integer (ms) | `100-30000` |
| `*SIZE`, `*LIMIT`, `*MAX*` | Integer | `10`, `100`, `1000`, `5000` |
| `*SAMPLE_RATE`, `*_RATE` | Float | `0.0-1.0` |
| `DD_ENV` | String | `production`, `staging`, `development` |
| `DD_SERVICE` | String | Service names |
| `*HOST*` | String | Hostnames/IPs |
| `*URL`, `*ENDPOINT` | String | URLs |
| `*PATH`, `*FILE` | String | File paths |
| `*KEY`, `*TOKEN` | String | Random hex strings |
| `*TAGS` | String | Comma-separated tags |
| `*PROPAGATION_STYLE` | String | `datadog`, `b3`, `tracecontext` |

## Statistics Summary

After all iterations, you'll see a summary like:

```
==================================================================
  Fuzzing Complete - Summary
==================================================================
Total iterations:    50
Successful runs:     45
Failed runs:         3
Timeout runs:        2
Logs directory:      ./fuzz-logs
```

## Exit Codes

- `0`: All runs completed without failures
- `1`: One or more runs failed (check logs)

## Tips

1. **Start Small**: Begin with 5-10 iterations to ensure everything works
2. **Review Logs**: Check `fuzz-logs/` for any issues or unexpected behavior
3. **Adjust Timeout**: Modify the `timeout 30s` in the script if your app needs more time to start
4. **Continuous Testing**: Run this regularly in CI/CD to catch configuration issues early
5. **Combine with Monitoring**: Watch application metrics during fuzzing to catch subtle issues

## Advanced Usage

### Custom Value Ranges

Edit the `generate_integer()` or `generate_string()` functions to customize value ranges for specific parameters.

### Integration with CI/CD

```bash
#!/bin/bash
# In your CI pipeline
if ! ./fuzz-configs.sh 100 "java -jar app.jar"; then
    echo "Fuzz testing failed!"
    exit 1
fi
```

### Parallel Execution

Run multiple fuzzer instances in parallel:

```bash
./fuzz-configs.sh 50 "java -jar app.jar" &
./fuzz-configs.sh 50 "java -jar app.jar" &
wait
```

## Troubleshooting

### Issue: "jq: command not found"
**Solution**: Install jq using your package manager (see Prerequisites)

### Issue: Script hangs
**Solution**: The 30-second timeout should prevent this. If it persists, check your application's shutdown behavior.

### Issue: All runs timeout
**Solution**: Increase the timeout value in the `run_fuzz_iteration()` function or check if your application is starting correctly.

### Issue: Permission denied
**Solution**: Make sure the script is executable: `chmod +x fuzz-configs.sh`

## Known Limitations

- Some parameter combinations might not be compatible (e.g., conflicting settings)
- Generated values are random but may not cover all edge cases
- File paths and URLs may not point to actual resources
- Some configurations require specific formats not captured by simple pattern matching

## Contributing

To add support for new parameter types:

1. Edit the `generate_value()` function
2. Add pattern matching for your parameter type
3. Implement value generation logic in the appropriate `generate_*()` function

## License

This script is part of the dd-trace-java project. Use according to the project's license.

## Support

For issues or questions:
- Check the logs in `fuzz-logs/`
- Review the dd-trace-java documentation
- Open an issue in the dd-trace-java repository

