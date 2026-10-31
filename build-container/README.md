# Podman builder

Builds the OsmAnd APK in a container, so no local JDK or Android SDK is needed. Only podman and git.

```sh
build-container/build-apk.sh
```

The first run builds the image (JDK 17 and the Android SDK packages from `versions.gradle`), clones
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources) next to this repository (the build reads
`../resources`) and fills the Gradle cache in `~/.cache/osmand-builder`. Later runs reuse all three.

The default task, `assembleVelopilotOpenglArm64Debug`, writes
`OsmAnd/build/outputs/apk/velopilotOpenglArm64/debug/OsmAnd-velopilot-opengl-arm64-debug.apk`.
It installs as a separate app, "VeloPilot" (`be.velopilot.app`), next to OsmAnd, and is
signed with `keystores/debug.keystore`, so newer builds install over older ones:

```sh
adb install -r OsmAnd/build/outputs/apk/velopilotOpenglArm64/debug/*.apk
```

To use the maps of the OsmAnd app instead of downloading them again: in OsmAnd, set *Settings > OsmAnd
settings > Main storage* to *Multiuser storage*; in VeloPilot, open *Settings > VeloPilot
settings > Maps from OsmAnd* and allow *Install unknown apps*. Android only lets apps with that permission
read another app's `Android/obb` folder; the app never installs anything. It reads OsmAnd's maps and does not
change them; maps downloaded in VeloPilot are stored in its own folder and take precedence.

On the first start, when OsmAnd is installed, the download button of the welcome screen first offers these steps
(`SharedMapsPrompt`); VeloPilot restarts once the access is allowed or the maps are moved.

VeloPilot's own resources are in `OsmAnd/res-velopilot`: launcher icon, splash logo, accent colours (the blue
of the logo) and the Xmind UI font. `branding/generate.py` makes the images and the font files from
`branding/velopilot.svg` and `branding/fonts/` (needs Inkscape, ImageMagick, fontTools and the Xmind font
installed); run it after changing those. The build writes the strings that mention OsmAnd, in all languages,
with VeloPilot instead (`generateVelopilotStrings` in `OsmAnd/build.gradle`); OsmAnd's services (OsmAnd Cloud,
Live, Pro) and the OsmAnd app whose maps VeloPilot uses keep their name.

`assembleNightlyFreeOpenglArm64Debug` builds the same code as "OsmAnd Nightly" (`net.osmand.dev`), without
access to the OsmAnd maps.

Other tasks and Gradle arguments go on the command line, for example
`build-container/build-apk.sh assembleVelopilotOpenglFatDebug` for all ABIs, or
`build-container/build-apk.sh :OsmAnd-java:test` to run the Java tests. `build-container/build-apk.sh shell`
opens a shell in the build container.

| Variable | Default | |
|---|---|---|
| `RESOURCES_DIR` | `../resources` | OsmAnd-resources checkout, cloned if missing |
| `UPDATE_RESOURCES` | `0` | `1` pulls OsmAnd-resources before building |
| `CACHE_DIR` | `~/.cache/osmand-builder` | Gradle and Kotlin/Native caches |
| `GRADLE_XMX` | `6g` | Gradle daemon heap |
| `REBUILD_IMAGE` | `0` | `1` rebuilds the image |
| `RUN_TESTS` | `0` | `1` keeps the OsmAnd-java tests in APK builds |

Packaging OsmAnd-java runs its whole test suite. Some upstream routing and search tests fail against the
current OsmAnd-resources (47 on `master` at 5e131f4eb5), so APK builds skip `:OsmAnd-java:test` unless
`RUN_TESTS=1` is set.

The OpenGL flavours take the native core from the prebuilt `OsmAndCore_android` snapshot on
builder.osmand.net. The legacy native library (`libosmand.so`) needs the Android NDK and the OsmAnd-core-legacy
sources, which this image does not include; without it the app uses its Java router.

## Releases on cdn.osmpedelec.be

GitLab CI (`.gitlab-ci.yml`) builds the releases and publishes them in an F-Droid repository at
<https://cdn.osmpedelec.be/fdroid/repo>, a Bunny CDN pull zone in front of a Bunny Storage zone. Users add that
repository with its fingerprint to F-Droid, Droid-ify, Neo Store or Obtainium and get updates from there.

### Releasing

1. On the default branch, run the manual job **release:tag** (Build > Pipelines, or the pipeline of the latest
   commit). It tags the commit as the next release. VeloPilot has its own version, independent of OsmAnd's:
   tags `vMAJOR.MINOR.PATCH` with versionCode MAJOR × 10000 + MINOR × 100 + PATCH (`v0.1.0` is 100, `v1.2.3`
   is 10203), so MINOR and PATCH stay below 100. The first release takes the versionName of the velopilot
   flavour in `OsmAnd/build.gradle` (0.1.0), later ones raise the patch number; set `RELEASE_VERSION` (e.g.
   `0.2.0`) in the job's "Run" form for another version. The tag message is the release notes: the subjects
   of the commits since the last release that are not on `master`, or `RELEASE_NOTES` when you set it.
2. The tag starts the release pipeline:
   - **apk:release** builds `assembleVelopilotOpenglArm64Release`, signed with the release key;
   - **fdroid:publish** downloads the published APKs from the storage zone, adds the new one and keeps the
     newest three (`KEEP_RELEASES`), writes the metadata from `fdroid/metadata/be.velopilot.app.yml` with the
     release notes of each version's tag, runs `fdroid update` to sign a new index and uploads the repository:
     new files first, the index last, then it deletes the removed APKs and purges `cdn.osmpedelec.be/fdroid/*`
     from the CDN cache. The job log ends with the link to add the repository.

A tag pushed from a local checkout (`git tag -a v0.3.0 -m "notes"`, then `git push <gitlab remote> v0.3.0`)
starts the same pipeline. To publish a release
again, retry its fdroid:publish job. To withdraw one, delete its APK from the storage zone and retry the
fdroid:publish job of the newest remaining release (or the whole pipeline once its APK artifact has expired).

On other branches, the manual job **apk:debug** builds a debug APK and keeps it as a job artifact for a week.

### Runner

The jobs run on a runner with the tag `osmpedelec`, so they never land on the instance runners, which have too
little memory for this build. Register a runner with the docker executor on a machine with at least 12 GB of
memory for the project (Settings > CI/CD > Runners > New project runner, tag `osmpedelec`). Keep the default
`volumes = ["/cache"]` in its `config.toml`: the jobs keep the Android SDK, the Gradle and Kotlin/Native caches
and OsmAnd-resources in `/cache/osmpedelec` (`CACHE_ROOT`), so only the first build downloads them. The jobs
use public images (eclipse-temurin, Debian with fdroidserver from its packages, buildpack-deps) and need no
privileged mode.

### Keys and variables

Once, create the keys locally:

```sh
build-container/release.sh init
```

This writes `apk-signing.p12` (signs the APK; Android only installs an update signed with the same key),
`fdroid-repo.p12` (signs the repository index; its fingerprint is what users check) and their passwords to
`~/.local/share/velopilot-release`, and prints the CI/CD variables for them. Back that folder up.
`build-container/release.sh variables` prints the variables again, `build-container/release.sh fingerprint`
the repository fingerprint and the link to add the repository.

CI/CD variables (Settings > CI/CD > Variables), all Protected and Masked:

| Variable | |
|---|---|
| `APK_KEYSTORE_BASE64`, `APK_KEY_PASSWORD` | APK signing key, from `release.sh variables` |
| `FDROID_KEYSTORE_BASE64`, `FDROID_KEYSTORE_PASSWORD` | repository key, from `release.sh variables` |
| `BUNNY_STORAGE_ZONE`, `BUNNY_STORAGE_PASSWORD` | Storage > zone > FTP & API Access in the Bunny dashboard |
| `BUNNY_STORAGE_HOST` | that page's hostname, if the zone's main region is not Falkenstein (`storage.bunnycdn.com`) |
| `BUNNY_API_KEY` | Account settings > API key; optional, purges the cached index after an upload |
| `RELEASE_TOKEN` | project access token with role Maintainer and scope `api`, for release:tag |

Protected variables only reach pipelines of protected branches and tags, so protect the default branch (it is by
default) and the release tags: Settings > Repository > Protected tags, `v*`, allowed to create: Maintainers.
release:tag creates the tag with `RELEASE_TOKEN`, because the job's own token cannot create tags.

Release builds are signed with a different key than the debug builds, so switching from a debug build to a
release means uninstalling the debug build first.
