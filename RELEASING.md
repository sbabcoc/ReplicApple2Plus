# Release Process

This document describes how to release ReplicApple2Plus to Maven Central
and GitHub.

[SoftCard2](https://github.com/sbabcoc/SoftCard2) depends on
ReplicApple2Plus (it builds against the `SlotCard` interface), so when
both are released, ReplicApple2Plus goes first, and SoftCard2 is then
built against the new release.

## Prerequisites

- GPG key configured for signing
- Sonatype Central Portal credentials configured in `~/.m2/settings.xml`
  under server ID `ossrh`
- The `gh` CLI, signed in to GitHub
- Gradle wrapper present in the project

## Version Numbering

The version is derived from Git tags by axion-release, with the tag prefix
`v`: tag `v1.0.0` builds version `1.0.0`. No manual version editing is
needed. An untagged commit builds the next SNAPSHOT version.

The README carries no version numbers to update; its Maven Central badge
follows the latest release by itself.

## Pre-Release Checklist

Before tagging, verify:

1. All changes are committed and pushed.
2. The project builds cleanly **with the ROM images and test disk images
   in place** (README.md, *Required ROM files*; the transfer tests' disks
   go in `src/test/resources/com/nordstrom/emulator/transfer/`). Without
   them their tests are skipped rather than run, so a clean build alone
   doesn't prove them:
   ```bash
   ./gradlew clean build
   ```
   Check that nothing was skipped that should have run:
   ```bash
   grep -l 'skipped="[1-9]' build/test-results/test/*.xml
   ```
3. The jars carry no third-party ROMs. The only `.rom` in either should be
   the transfer card's own firmware, `hostfiles-firmware.rom`:
   ```bash
   unzip -l build/libs/ReplicApple2Plus-*.jar | grep '\.rom'
   ```
4. README.md and the design documents are up to date.

> **IMPORTANT**: Do a full build verification BEFORE tagging. If a bug is
> found after tagging, the tag must be deleted locally and remotely, the
> bug fixed, and the tag recreated on the new commit. Moving tags after
> publishing to Maven Central is not possible.

## Release Steps

### 1. Tag the release

```bash
git tag v1.0.0
git push origin v1.0.0
```

Verify it produces the release version (no `-SNAPSHOT` suffix):

```bash
./gradlew properties | grep "^version:"
```

### 2. Publish and install

`publish` and `closeAndReleaseStagingRepositories` MUST run in the same
Gradle invocation -- the staging repository ID is not persisted between
invocations.

```bash
./gradlew publish closeAndReleaseStagingRepositories
./gradlew install
```

`install` puts the release in the local Maven repository, where SoftCard2
resolves it.

### 3. Verify Maven Central publication

Check that the artifacts appear at:
**https://central.sonatype.com/publishing/deployments**

Propagation to Maven Central typically takes 10-30 minutes after the
staging repository is released.

### 4. Create the GitHub release

```bash
./gradlew distZip
gh release create v1.0.0 build/distributions/ReplicApple2Plus-1.0.0.zip \
    --title "ReplicApple2Plus 1.0.0" --notes "..."
```

The distribution zip holds the jar and launch scripts, without ROMs; its
users supply their own with `--roms DIR`.

### 5. Installers

jpackage builds an installer only for the platform it runs on, so the
**Build Installers** workflow (`.github/workflows/build-installers.yml`)
builds them on GitHub's runners: the `.deb` on x86-64 Linux, the `.msi`
on Windows (installing WiX 3 if needed), and the `.dmg` on Apple Silicon
macOS. Publishing the release in step 4 starts it, and it attaches each
installer to the release. Check the Actions tab for its result.

To build installers for an earlier release, or rebuild them, run the
workflow by hand from the Actions tab (or `gh workflow run build-installers.yml
-f tag=v1.0.3`); an installer already attached under the same name is
replaced.

An installer for another platform -- an `arm64` `.deb`, say -- can still
be built on a machine of that kind and attached by hand:

```bash
git checkout v1.0.0
./gradlew jpackage
gh release upload v1.0.0 build/jpackage/<installer file>
```

Installers carry no ROMs either. An installed app gets no command-line
options, so it works from an emulator folder the user chooses on its
first start: one already set up (it holds `slots.ini`), or a new one,
which it sets up with `roms/`, `plugins/` and a starter `slots.ini`, then
asks for the ROM images to be copied into `roms/`.
Installers that aren't
code-signed draw a warning from macOS Gatekeeper and Windows SmartScreen
when first opened.

## Moving a Tag After a Bug Fix

If a bug is found after tagging but before publishing to Maven Central:

```bash
# Fix the bug and commit
git add <files>
git commit -m "Fix <description>"

# Delete tag locally and remotely
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0

# Recreate tag on new HEAD
git tag v1.0.0
git push origin v1.0.0
```

If the bug is found after publishing to Maven Central, the release cannot
be modified. A new patch release (e.g. `1.0.1`) must be made.

## Local Development Builds

For local development, use `install` instead of `publish`:

```bash
./gradlew install
```

## Dropping Failed Staging Repositories

If a publish fails after the staging repository is created, drop it via:

```bash
curl -X POST \
  -u "OSSRH_USERNAME:OSSRH_PASSWORD" \
  "https://ossrh-staging-api.central.sonatype.com/service/local/staging/bulk/drop" \
  -H "Content-Type: application/json" \
  -d '{"data":{"stagedRepositoryIds":["REPO_ID"]}}'
```

The staging repository ID is printed in the build output, e.g.:
```
Created staging repository 'com.nordstrom--abc123...' at https://...
```

A 204 response (no body) indicates success.

## Troubleshooting

**"No staging repository with name ossrh created"** -- `publish` and
`closeAndReleaseStagingRepositories` were run in separate Gradle
invocations. Always run them together in a single command.

**An installed app fails as soon as a menu opens, or never shows its
window** -- the runtime image lacks a JDK module the code now uses. Check
with `jdeps --print-module-deps --ignore-missing-deps build/classes/java/main`,
and add it to `runtimeModules` in `build.gradle`.

**A `.rom` other than `hostfiles-firmware.rom` in a jar** -- a new ROM
image was added under `src/main/resources` without being added to
`thirdPartyRoms` in `build.gradle`. Add it there before publishing.
