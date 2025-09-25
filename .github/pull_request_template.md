# What Does This Do

# Motivation

# Additional Notes

# Contributor Checklist

- Format the title [according the contribution guidelines](https://github.com/DataDog/dd-trace-java/blob/master/CONTRIBUTING.md#title-format)
- Assign the `type:` and (`comp:` or `inst:`) labels in addition to [any useful labels](https://github.com/DataDog/dd-trace-java/blob/master/CONTRIBUTING.md#labels)
- Don't use `close`, `fix` or any [linking keywords](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue#linking-a-pull-request-to-an-issue-using-a-keyword) when referencing an issue.  
  Use `solves` instead, and assign the PR [milestone](https://github.com/DataDog/dd-trace-java/milestones) to the issue
- Update the [CODEOWNERS](https://github.com/DataDog/dd-trace-java/blob/master/.github/CODEOWNERS) file on source file addition, move, or deletion
- Update the [public documentation](https://docs.datadoghq.com/tracing/trace_collection/library_config/java/) in case of new configuration flag or behavior

Jira ticket: [PROJ-IDENT]

<!--
# Opening vs Drafting a PR:
When opening a pull request, please open it as a draft to not auto assign reviewers before you feel the pull request is in a reviewable state.

# Linking a JIRA ticket:
Please link your JIRA ticket by adding its identifier between brackets (ex [PROJ-IDENT]) in the PR description, not the title.
This requirement only applies to Datadog employees.
-->
