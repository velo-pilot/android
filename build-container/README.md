# Podman builder

Builds the OsmAnd APK in a container, so no local JDK or Android SDK is needed. Only podman and git.

```sh
build-container/build-apk.sh
```

The first run builds the image (JDK 17 and the Android SDK packages from `versions.gradle`), clones
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources) next to this repository (the build reads
`../resources`) and fills the Gradle cache in `~/.cache/osmand-builder`. Later runs reuse all three.

The default task, `assembleOsmPedelecOpenglArm64Debug`, writes
`OsmAnd/build/outputs/apk/osmPedelecOpenglArm64/debug/OsmAnd-osmPedelec-opengl-arm64-debug.apk`.
It installs as a separate app, "OsmPedelec" (`be.osmpedelec.app`), next to OsmAnd, and is
signed with `keystores/debug.keystore`, so newer builds install over older ones:

```sh
adb install -r OsmAnd/build/outputs/apk/osmPedelecOpenglArm64/debug/*.apk
```

To use the maps of the OsmAnd app instead of downloading them again: in OsmAnd, set *Settings > OsmAnd
settings > Data storage folder* to *Multiuser storage*; in OsmPedelec, open *Settings > OsmAnd
settings > Maps from OsmAnd* and allow *Install unknown apps*. Android only lets apps with that permission
read another app's `Android/obb` folder; the app never installs anything. It reads OsmAnd's maps and does not
change them; maps downloaded in OsmPedelec are stored in its own folder and take precedence.

`assembleNightlyFreeOpenglArm64Debug` builds the same code as "OsmAnd Nightly" (`net.osmand.dev`), without
access to the OsmAnd maps.

Other tasks and Gradle arguments go on the command line, for example
`build-container/build-apk.sh assembleOsmPedelecOpenglFatDebug` for all ABIs, or
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

`build-container/release.sh` builds a signed release APK and publishes it in an F-Droid repository at
<https://cdn.osmpedelec.be/fdroid/repo>, a Bunny CDN pull zone in front of a Bunny Storage zone. Users add that
repository (with its fingerprint) to F-Droid, Droid-ify, Neo Store or Obtainium and get updates from there.

Once, set up the keys and the repository:

```sh
build-container/release.sh init
```

This creates `~/.local/share/osmpedelec-release` (`RELEASE_HOME`) with

- `apk-signing.p12`, the key that signs the APK. Android only installs an update signed with the same key.
- `fdroid/`, the F-Droid repository: `config.yml`, `keystore.p12` (signs the index; its fingerprint is
  what users check), `releases` (versionCode and versionName of every release), `metadata/`, `repo/` and
  `archive/`. This is the source of truth; the storage zone is a copy.
- `release.env`, the passwords and the Bunny credentials.

Back that folder up. Then fill in `BUNNY_STORAGE_ZONE`, `BUNNY_STORAGE_PASSWORD` and `BUNNY_STORAGE_HOST`
(Storage > zone > FTP & API Access in the Bunny dashboard) and, to purge the cached index after an upload,
`BUNNY_API_KEY` (Account settings > API key) in `release.env`.

For each release, commit everything and run

```sh
build-container/release.sh
```

which

1. takes the next release number for the OsmAnd version in `OsmAnd/build.gradle`: OsmAnd 5.5.0
   (versionCode 5550) gives `5.5.0-sp1`, `5.5.0-sp2`, … with versionCode 555001, 555002, …;
2. builds `assembleOsmPedelecOpenglArm64Release` in the podman builder, signed with `apk-signing.p12`;
3. copies the APK and the release notes (`NOTES_FILE`, or the subjects of the commits since the last release
   that are not on `origin/master`; the first release has none unless `NOTES_FILE` is set) into the
   repository, writes the app's metadata from `fdroid/metadata/be.osmpedelec.app.yml` in this folder
   plus an entry per release, and runs `fdroid update` in a container, which signs the new index;
4. tags the commit `v5.5.0-spN` (locally; push it yourself);
5. uploads the repository: new and changed files first, the index last, deletes files that `fdroid update`
   moved to the archive and purges `cdn.osmpedelec.be/fdroid/*` from the CDN cache.

If the upload fails, `build-container/release.sh publish` uploads again without building;
`DRY_RUN=1 build-container/release.sh publish` only lists what it would change.
`build-container/release.sh fingerprint` prints the repository fingerprint and the link to add the repository
with it. `build-container/release.sh shell` opens a shell with fdroidserver in the repository, e.g. to
remove a release: delete its APK from `repo/` and its line from `releases`, remove its entry under `Builds`
in `metadata/be.osmpedelec.app.yml` (and fix `CurrentVersion`), run `fdroid update`, then `publish`.

Release builds are signed with a different key than the debug builds, so switching from a debug build to a
release means uninstalling the debug build first.
