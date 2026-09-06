---
name: compukters-release
description: Use when building, reviewing, or claiming readiness of a distributable Compukters JAR or tagged release, especially when native-platform coverage matters. Do not use for ordinary development runs with no artifact or release claim.
---

# Compukters Release

Distinguish the artifact being produced from the readiness claim being made. Read `docs/VERIFICATION.md` before
selecting commands.

## Select the artifact contract

- For a production-format artifact using the locally configured native resources, use
  `:v26_1-neoforge:buildProductionUniversalJar`. Its historical name does not prove Linux-and-Windows coverage.
- For a distributable universal release candidate, first require `verifyLocalFull` on the exact candidate revision,
  then use `:v26_1-neoforge:buildReleaseUniversalJar` from the clean exact tag with configured release runtime bundles.

Use the existing Gradle tasks and their archive, release-state, resource, and packaged-native checks. Do not recreate
them in an ad hoc shell pipeline or weaken a failing release precondition to obtain an artifact.

## Report exact evidence

Record the parent revision, pinned VM submodule revision, exact JAR path and digest, packaged native resource paths,
and verification commands. State whether the evidence supports only a local production artifact or a tagged universal
release. Do not infer one from the other.

Building and verification do not authorize tagging, pushing, uploading, publishing, or creating an external release.
Perform those mutations only when the user explicitly requests them.
