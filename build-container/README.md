# Podman builder

Builds the OsmAnd APK in a container, so no local JDK or Android SDK is needed. Only podman and git.

```sh
build-container/build-apk.sh
```

The first run builds the image (JDK 17 and the Android SDK packages from `versions.gradle`), clones
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources) next to this repository (the build reads
`../resources`) and fills the Gradle cache in `~/.cache/osmand-builder`. Later runs reuse all three.

The default task, `assembleSpeedPedelecOpenglArm64Debug`, writes
`OsmAnd/build/outputs/apk/speedPedelecOpenglArm64/debug/OsmAnd-speedPedelec-opengl-arm64-debug.apk`.
It installs as a separate app, "OsmAnd Speed Pedelec" (`be.spedelec.osmand.app`), next to OsmAnd, and is
signed with `keystores/debug.keystore`, so newer builds install over older ones:

```sh
adb install -r OsmAnd/build/outputs/apk/speedPedelecOpenglArm64/debug/*.apk
```

To use the maps of the OsmAnd app instead of downloading them again: in OsmAnd, set *Settings > OsmAnd
settings > Data storage folder* to *Multiuser storage*; in OsmAnd Speed Pedelec, open *Settings > OsmAnd
settings > Maps from OsmAnd* and allow *Install unknown apps*. Android only lets apps with that permission
read another app's `Android/obb` folder; the app never installs anything. It reads OsmAnd's maps and does not
change them; maps downloaded in OsmAnd Speed Pedelec are stored in its own folder and take precedence.

`assembleNightlyFreeOpenglArm64Debug` builds the same code as "OsmAnd Nightly" (`net.osmand.dev`), without
access to the OsmAnd maps.

Other tasks and Gradle arguments go on the command line, for example
`build-container/build-apk.sh assembleSpeedPedelecOpenglFatDebug` for all ABIs, or
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
