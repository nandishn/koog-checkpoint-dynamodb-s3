# Releasing

This project publishes with the Gradle Maven Publish Plugin.

## Prerequisites

- A verified Maven Central namespace for `io.github.nandishn`.
- A Central Portal user token.
- A GPG key for artifact signing.
- A public repository URL that matches the POM metadata.

Do not commit Maven Central credentials or signing keys.

## Local Dry Run

```bash
./gradlew check publishToMavenLocal
```

To test release coordinates locally:

```bash
./gradlew -PreleaseVersion=0.1.0 publishToMavenLocal
```

Local Maven publication does not require signing. Maven Central publication does.

## GitHub Secrets

Configure these repository secrets before running the release workflow:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_IN_MEMORY_KEY_ID`
- `SIGNING_IN_MEMORY_KEY_PASSWORD`

The Maven Central username and password are Central Portal user-token values, not your login password.

## Release

Run the `Release` workflow from GitHub Actions and provide a non-SNAPSHOT version such as `0.1.0`.

The workflow runs unit checks, LocalStack integration tests, and `publishToMavenCentral`. After upload, inspect and release the deployment in Central Portal.

For local Maven Central publishing outside GitHub Actions, keep credentials in `~/.gradle/gradle.properties` and pass:

```bash
./gradlew -PreleaseVersion=0.1.0 -PsignAllPublications=true publishToMavenCentral
```
