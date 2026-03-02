# AI Tools Integration Guide

> **Note**: This document is currently under development and will be populated with guidance on using AI coding assistants effectively with dd-trace-java.

## The AI tools

This section describes the AI tools and integrations available for dd-trace-java development.

**Index:**
- [MCP Servers Integration (Beta)](#mcp-servers-integration-beta)
  - [Datadog MCP Server Setup](#datadog-mcp-server-setup-for-dd-trace-java)
- [Claude CLI](#claude-cli)
- [GitHub CLI (gh)](#github-cli-gh)
- [GitLab CLI (glab)](#gitlab-cli-glab)


### MCP Servers Integration (Beta)

Model Context Protocol (MCP) servers extend AI capabilities with specialized tools and integrations. The dd-trace-java repository provides several MCP server configurations to enhance your development workflow. MCP servers can be used with any compatible AI tool, including Cursor and Claude CLI.

**Available MCP Servers:**

- **Datadog MCP Server**: Provides CI Visibility integration including pipeline event search and aggregation, test event analysis, flaky test detection, code coverage summaries, and PR insights. Pre-configured in the repository via `.mcp.json` (for Claude CLI and other compatible tools). Cursor users need to set up `.cursor/mcp.json` manually (see setup instructions below). 

To set up MCP servers, follow the specific setup guides for each server. All MCP servers integrate seamlessly with compatible AI tools, providing enhanced context and capabilities for working with the dd-trace-java repository.

#### Datadog MCP Server Setup for dd-trace-java

The Datadog MCP server connects Cursor's/Claude AI assistant to Datadog's CI Visibility platform, letting you query pipeline events, test results, flaky tests, code coverage, and PR insights directly from the chat.

##### Setup

**1. Repository Configuration**

The repository includes the MCP server configuration for Claude CLI and other compatible tools in `.mcp.json`:

```json
{
  "mcpServers": {
    "datadog": {
      "type": "http",
      "url": "https://mcp.datadoghq.com/api/unstable/mcp-server/mcp?toolsets=software-delivery"
    }
  }
}
```

For **Cursor** users, the `.cursor/` directory is not tracked in git. Create the file `.cursor/mcp.json` manually with the following content:

```json
{
  "mcpServers": {
    "datadog": {
      "url": "https://mcp.datadoghq.com/api/unstable/mcp-server/mcp?toolsets=software-delivery",
      "headers": {}
    }
  }
}
```

**2. Authentication via SSO**

The Datadog MCP server uses SSO for authentication. No API or App keys are required.

1. Open your AI tool in the dd-trace-java repository
2. Trigger the **login** command on the `datadog` MCP server:
   - **Cursor**: Go to the MCP settings panel and trigger the login command. For the in-terminal agent use `/mcp login`.
   - **Claude CLI**: Run `/mcp` to see server status, then follow the login prompt.
3. A browser tab opens automatically for Datadog SSO login
4. Complete the login in your browser — the tool picks up the session automatically

After logging in, the MCP tools are immediately available in the AI chat.

##### Available Tools

The repository configuration enables the **software-delivery** toolset. See the [official Datadog MCP toolsets documentation](https://docs.datadoghq.com/bits_ai/mcp_server/#toolsets:~:text=MCP%20Server.-,Toolsets,-The%20Datadog%20MCP) for the full list of available tools.

##### Example Prompts

```
Show me the latest failed CI jobs on the main branch of DataDog/dd-trace-java
```

```
Find the top 10 flaky tests in dd-trace-java sorted by failure rate
```

```
What issues are blocking PR #1234 in DataDog/dd-trace-java?
```

```
What's the P95 pipeline duration for dd-trace-java over the last 30 days?
```

### Claude CLI

The dd-trace-java repository provides native support for **Claude CLI** (Anthropic's command-line interface for Claude) through a dedicated configuration in the `.claude/` directory.

#### Configuration Files

```
.claude/
├── CLAUDE.md           # Claude-specific instructions and rules pointer
├── settings.json       # Claude CLI settings (permissions, environment, MCP enablement)

.mcp.json               # MCP server configuration (shared across AI tools)
```

* **`.claude/CLAUDE.md`**: Contains Claude-specific instructions and points to the shared rules in `AGENTS.md`
* **`.claude/settings.json`**: Configures Claude CLI behavior, including default permission mode, environment variables, and MCP server enablement (e.g., the Datadog MCP server)
* **`.mcp.json`**: Defines MCP server connections at the repository root. Claude CLI automatically discovers this file and makes the configured servers available. Currently includes the Datadog MCP server for CI Visibility integration

### GitHub CLI (gh)

The AI tools are configured to use the **GitHub CLI** (`gh`) for all interactions with GitHub (github.com), including repository management, pull requests, issues, and CI/CD operations. The AI assistant will automatically use `gh` commands when performing GitHub-related tasks.

#### Installation

```bash
brew install gh
```

#### Authentication

```bash
gh auth login
```

To verify your authentication status:

```bash
gh auth status
```

### GitLab CLI (glab)

The AI tools are configured to use the **GitLab CLI** (`glab`) for all interactions with GitLab (gitlab.ddbuild.io), including pipeline management, merge requests, and CI/CD operations. The AI assistant will automatically use `glab` commands when performing GitLab-related tasks.

#### Installation

```bash
brew install glab
```

#### Authentication

You will need a **personal access token** to authenticate with GitLab. You can create one from your GitLab profile at **Settings > Access Tokens** on `gitlab.ddbuild.io`.

Once you have your token, authenticate:

```bash
glab auth login --hostname gitlab.ddbuild.io
```

To set the default host:

```bash
glab config set host gitlab.ddbuild.io
```