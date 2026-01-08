#!/bin/bash
#
# Docker-based Test Runner for Resilience4j Instrumentation
# Use this if Java is not installed locally
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Docker-based Test Runner for Resilience4j Instrumentation${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}ERROR: Docker not found. Please install Docker Desktop:${NC}"
    echo -e "${YELLOW}  https://www.docker.com/products/docker-desktop${NC}"
    echo ""
    echo -e "${YELLOW}Alternatively, install Java 17+ and use:${NC}"
    echo -e "  ./run-resilience4j-tests.sh --all"
    exit 1
fi

echo -e "${GREEN}✓ Docker found${NC}"
echo ""

# Build test image
echo -e "${YELLOW}Building test Docker image...${NC}"
cat > "$SCRIPT_DIR/Dockerfile.test" <<'EOF'
FROM gradle:8.5-jdk17

WORKDIR /workspace

# Copy repository
COPY . .

# Set working directory to project root
WORKDIR /workspace

# Run tests
CMD ["./gradlew", ":dd-java-agent:instrumentation:resilience4j:resilience4j-comprehensive:test", "--console=plain"]
EOF

# Build the image
if docker build -t dd-trace-resilience4j-test -f "$SCRIPT_DIR/Dockerfile.test" "$SCRIPT_DIR"; then
    echo -e "${GREEN}✓ Docker image built${NC}"
else
    echo -e "${RED}✗ Failed to build Docker image${NC}"
    exit 1
fi
echo ""

# Run tests in container
echo -e "${YELLOW}Running tests in Docker container...${NC}"
echo ""

if docker run --rm \
    -v "$SCRIPT_DIR:/workspace" \
    dd-trace-resilience4j-test; then
    echo ""
    echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  All tests passed! ✓${NC}"
    echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
    exit 0
else
    echo ""
    echo -e "${RED}════════════════════════════════════════════════════════${NC}"
    echo -e "${RED}  Some tests failed! ✗${NC}"
    echo -e "${RED}════════════════════════════════════════════════════════${NC}"
    exit 1
fi
