# Publishing to Maven Central

Zea TCP Transport publishes through the Sonatype Central Publisher Portal.
The [GitHub Actions workflow](../.github/workflows/publish-maven-central.yml)
automates deployment when a GitHub Release is published.

## Release Workflow

The workflow uses the `release` event with type `published`. Saving a draft,
pushing a commit or pushing a tag alone does not publish a Maven artifact.
Published regular releases and prereleases both trigger the workflow; their tags
must match the POM version. Snapshot versions are rejected.

The job:

1. Checks out the exact release tag and sets up Temurin Java 21.
2. Checks that the tag is the POM version, optionally prefixed with `v`.
3. Checks that all four publishing secrets are populated without printing them.
4. Runs the regression suite and strict main/test English JavaDoc checks.
5. Builds sources and JavaDoc JARs, signs the artifacts, and publishes them to Central.
6. Waits for the Central deployment to reach `PUBLISHED` before reporting success.

Checkout and Java setup actions are pinned to full commit identifiers. The workflow
needs only `contents: read` GitHub permissions. Central authentication uses a Portal
user token, rather than `GITHUB_TOKEN`. Runs for the same tag are serialized without
cancelling an active publication. The job timeout is 60 minutes; the Central plugin
waits up to its default 30-minute deployment deadline.

## Required Repository Secrets

In the GitHub repository, open **Settings → Secrets and variables → Actions →
New repository secret** and add:

| Name | Required Content |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | The username returned when generating a Central Portal user token |
| `MAVEN_CENTRAL_PASSWORD` | The password returned with that token |
| `GPG_PRIVATE_KEY` | Complete multiline ASCII-armored private signing key, including its BEGIN/END lines |
| `GPG_PASSPHRASE` | The passphrase for that exported private key |

The workflow expects a passphrase-protected signing key and checks all four values.
It passes the key and passphrase to the Maven GPG plugin's Bouncy Castle signer
through `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE`. The private key stays in the
step environment; no key file or interactive GPG agent is needed on the runner.
Secrets are supplied only to the secret check and publication steps.

### Central Portal Token and Namespace

Sign in to the [Central Publisher Portal](https://central.sonatype.com/).
The account associated with the token must have publishing access to the
`com.akakata` namespace used by this project's `groupId`.
See [namespace registration](https://central.sonatype.org/register/namespace/).

Open [User Tokens](https://central.sonatype.com/usertoken), generate a token, and
store the returned username/password pair in the two Central repository secrets.
These values are token credentials, rather than your account email and login password.
Track the token's expiry and replace the secrets when rotating it. Sonatype documents
the process in [Generating a Portal Token](https://central.sonatype.org/publish/generate-portal-token/).

`actions/setup-java` generates Maven settings for server ID `central`, referencing
the two Central environment variables. The release profile's publishing server ID
matches that entry.

### Signing Key Preparation

Use an existing unexpired signing key, or generate one locally:

```bash
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
```

Select a passphrase-protected key with signing capability. Replace
`YOUR_KEY_FINGERPRINT` in the following commands with its full fingerprint.
Upload the public key to a Central-supported key server:

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys YOUR_KEY_FINGERPRINT
```

Export the complete private key for `GPG_PRIVATE_KEY`. If you use the GitHub CLI,
this command sends the export directly to the repository secret:

```bash
gpg --armor --export-secret-keys YOUR_KEY_FINGERPRINT | gh secret set GPG_PRIVATE_KEY
```

Run this from a checkout of the intended repository. Add the same private key's
passphrase as `GPG_PASSPHRASE` through the GitHub settings page. Upload the public
key again after extending its expiry. See Sonatype's
[PGP signature guide](https://central.sonatype.org/publish/requirements/gpg/) and
the [Maven GPG plugin](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html).

## Create a Release

1. Set the desired version in `pom.xml`. The current version is `0.8.0`.
2. Update version-specific documentation, commit the changes and push them with
   the publishing workflow to GitHub.
3. In GitHub Releases, select or create a tag matching that POM, such as `v0.8.0`
   or `0.8.0`, and publish the Release.
4. Open the **Publish to Maven Central** Actions run and inspect its result.

The tag points to the POM and sources being released. Changing the default branch
after creating the tag does not change that tagged version. Release versions are
[immutable](https://central.sonatype.org/publish/requirements/immutability/): a
published `groupId:artifactId:version` cannot be overwritten. Use a new POM version
and a new tag for the next release.

The workflow uses the `release` profile with automatic publishing enabled and
`waitUntil=published`. A successful job confirms Portal publication; indexing in
Central's search interface can appear later. Re-running a successful publication
of the same version is not a way to replace its artifacts.

## Verify a Release Locally

Normal development builds remain unsigned:

```bash
mvn --batch-mode --no-transfer-progress clean verify javadoc:javadoc javadoc:test-javadoc
```

To build and sign the release artifacts without deploying them, provide your
signing key and passphrase through `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE`
in a local environment, then run:

```bash
mvn --batch-mode --no-transfer-progress -Prelease clean verify \
  -Dgpg.signer=bc -Dgpg.bestPractices=true
```

This stops at `verify`, so it does not invoke publication. The main JAR, sources
JAR, JavaDoc JAR and their detached signatures appear under `target/`.

To exercise the deploy lifecycle while disabling Central publication, the same
signing environment can use:

```bash
mvn --batch-mode --no-transfer-progress -Prelease deploy \
  -Dgpg.signer=bc -Dgpg.bestPractices=true -Dcentral.skipPublishing=true
```

With Central Publishing 0.11.0, this skips artifact staging and upload; it does not
produce a deployment ZIP. The signed release artifacts remain under `target/`.
The publisher still initializes its client from the `central` Maven server entry.
Actual publication generates `target/central-publishing/central-bundle.zip` before
uploading it. The `central.skipPublishing` property defaults to `false`; the GitHub
release workflow uses actual deployment. The publisher plugin is enabled only by
the `release` profile. Its configuration follows the
[Central Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/).

## Troubleshooting

| Failure | Action |
| --- | --- |
| Tag/POM mismatch | Use a tag matching the POM at that commit; the workflow does not rewrite versions |
| Snapshot version | Choose a release version before publishing the GitHub Release |
| Missing secret | Populate the named repository secret, then rerun the failed workflow |
| Central authentication or namespace failure | Verify the generated token pair, token expiry and publishing access to `com.akakata` |
| Signing failure | Verify the full private-key export, its passphrase and signing capability |
| Central cannot validate the signature | Check public-key availability and key expiry |
| Sources or JavaDoc validation failure | Fix the build or English JavaDoc errors before releasing |
| Version already published | Increment the POM version and create a new Release tag |
| Publication wait timeout | Check the deployment in the Central Portal before retrying; publication may already have completed |

This workflow deploys release artifacts to Maven Central. Snapshot deployment and
GitHub Packages require separate workflows and repository configuration.
