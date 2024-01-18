# GitHub Actions Documentation

This lists and describes the repository GitHub actions.

## Release Management

### add-assets-to-release [🔗](add-assets-to-release.yaml)

_Trigger:_ When a release is published.

_Actions:_
* Ensure the release name is properly formatted (using `x.y.z` format),
* Download `dd-java-agent`, `dd-trace-api` and `dd-trace-ot` artifacts from Sonatype (aka _Maven Central_ and upload them to the release (`dd-java-agent` will also be uploaded without version number).

_Recovery:_ Download artifacts and upload them manually to the release.

### add-milestone-to-pull-requests [🔗](add-milestone-to-pull-requests.yaml)

_Trigger:_ When a PR to `master` is closed.

_Action:_ Get the last (by name) opened milestone and affect it to the closed pull request.

_Recovery:_ Attach the milestone by hand to the PR.

### create-next-milestone [🔗](create-next-milestone.yaml)

_Trigger:_ When closing a milestone.

_Action:_ Create a new milestone by incrementing minor version.

_Comment:_ Already done when closing a tag. To delete?

### draft-release-notes-on-tag [🔗](draft-release-notes-on-tag.yaml)

_Trigger:_ When creating a tag, or manually (providing a tag)

_Actions:_

* Fetch merged pull requests from the related tag milestone,
* Generate changelog draft,
* Create a new draft release for given tag with the generated changelog.

_Recovery:_ Manually trigger the action again on the relevant tag.

## increment-milestones-on-tag [🔗](increment-milestones-on-tag.yaml)

_Trigger:_ When creating a tag. Release Candidate tags containing "-RC" or "-rc" will skip this.

_Actions:_
* Close the milestone related to the tag,
* Create a new milestone by incrementing minor version.

_Recovery:_ Manually close the related milestone and create a new one.

_Notes:_ This actions will handle _minor_ releases only.
As there is no milestone for _patch_ releases, it won't close and create _patch_ releated milestone.

## update-download-releases [🔗](update-download-releases.yaml)

_Trigger:_ When a release is published.

_Action:_ Update the _download releases_ with the latest release artifact.

_Recovery:_ Download artifacts and upload them manually to the related _download release_.

_Notes:_ _Download releases_ are special GitHub releases with fixed URL and tags, but rolling artifacts to provided stable download links (ex [latest](https://github.com/DataDog/dd-trace-java/releases/tag/download-latest) and [latest-v1](https://github.com/DataDog/dd-trace-java/releases/tag/download-latest-v1)).

## update-issues-on-release [🔗](update-issues-on-release.yaml)

_Trigger:_ When a release is published. Releases of type `prereleased` should skip this.

_Action:_
* Find all issues related to the release by checking the related milestone,
* Add a comment to let know the issue was addressed by the newly published release,
* Close all those issues.

_Recovery:_ Check at the milestone for the related issues and update them manually.

## Code Quality and Security

### comment-on-submodule-update [🔗](comment-on-submodule-update.yaml)

_Trigger:_ When creating a PR commits to `master` or a `release/*` branch with a Git Submodule update.

_Action:_ Notify the PR author through comments that about the Git Submodule update.

### codeql-analysis [🔗](codeql-analysis.yml)

_Trigger:_ When pushing commits to `master` or any pull request to `master`.

_Action:_ Run GitHub CodeQL action and upload result to GitHub security tab.

### trivy-analysis [🔗](trivy-analysis.yml)

_Trigger:_ When pushing commits to `master` or any pull request to `master`.

_Action:_ Run Trivy security scanner on built artifacts and upload result to GitHub security tab.

### gradle-wrapper-validation [🔗](gradle-wrapper-validation.yaml.disabled)

**DISABLED** - GitHub provides a way to disable actions rather than changing their extensions.

_Comment:_ To delete?

## Lib Injection

### lib-injection [🔗](lib-injection.yaml)

_Trigger:_ When pushing commits to `master`, release branches or any PR targetting `master`, and when creating tags.

_Actions:_
* Build and publish to GHCR a Docker image with the Java tracer agent,
* Build lib-injection and run its system tests with the build Java agent.

### lib-injection-manual-release [🔗](lib-injection-manual-release.yaml)

_Trigger:_ When manually triggered.

_Action:_ Build and publish to GHCR a Docker image with the given Java tracer version.

### lib-injection-prune-registry [🔗](lib-injection-prune-registry.yaml)

_Trigger:_ Every week or manually.

_Action:_ Clean up old lib-injection Docker images from GHCR.

_Recovery:_ Manually trigger the action again.
