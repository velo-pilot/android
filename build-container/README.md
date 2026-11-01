# Podman builder

Builds the OsmAnd APK in a container, so no local JDK or Android SDK is needed. Only podman and git.

```sh
build-container/build-apk.sh
```

The first run builds the image (JDK 17 and the Android SDK packages from `versions.gradle`), clones
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources) next to this repository (the build reads
`../resources`) and fills the Gradle cache in `~/.cache/osmand-builder`. Later runs reuse all three.

The default task, `assembleVelopilotDevOpenglArm64Debug`, writes
`OsmAnd/build/outputs/apk/velopilotDevOpenglArm64/debug/OsmAnd-velopilotDev-opengl-arm64-debug.apk`.
It installs as "VeloPilot Dev" (`be.velopilot.dev`), next to OsmAnd and next to the VeloPilot release app
(`be.velopilot.app`, flavour `velopilot`), and is signed with `keystores/debug.keystore`, so newer builds
install over older ones:

```sh
adb install -r OsmAnd/build/outputs/apk/velopilotDevOpenglArm64/debug/*.apk
```

To use the maps of the OsmAnd app instead of downloading them again: in OsmAnd, set *Settings > OsmAnd
settings > Main storage* to *Multiuser storage*; in VeloPilot, open *Settings > VeloPilot
settings > Maps from OsmAnd* and allow *Install unknown apps*. Android only lets apps with that permission
read another app's `Android/obb` folder; the app never installs anything. It reads OsmAnd's maps and does not
change them; maps downloaded in VeloPilot are stored in its own folder and take precedence.

On the first start, when OsmAnd is installed, the download button of the welcome screen first offers these steps
(`SharedMapsPrompt`); VeloPilot restarts once the access is allowed or the maps are moved.

VeloPilot's own resources are in `OsmAnd/res-velopilot`: launcher icon, splash logo, accent colours (the blue
of the logo) and the Xmind UI font. The dev and beta builds use them too, with their own launcher icon from
`OsmAnd/res-velopilot-dev` (an α instead of the P) and `OsmAnd/res-velopilot-beta` (a β). `branding/generate.py`
makes the images and the font files from `branding/velopilot*.svg` and `branding/fonts/` (needs Inkscape,
ImageMagick, fontTools and the Xmind font installed); run it after changing those. The build writes the strings that mention OsmAnd, in all languages,
with VeloPilot instead (`generateVelopilotStrings` in `OsmAnd/build.gradle`); OsmAnd's services (OsmAnd Cloud,
Live, Pro) and the OsmAnd app whose maps VeloPilot uses keep their name.

`assembleNightlyFreeOpenglArm64Debug` builds the same code as "OsmAnd Nightly" (`net.osmand.dev`), without
access to the OsmAnd maps.

Other tasks and Gradle arguments go on the command line, for example
`build-container/build-apk.sh assembleVelopilotDevOpenglFatDebug` for all ABIs,
`build-container/build-apk.sh assembleVelopilotOpenglArm64Debug` for a debug build of the release app, or
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

## Builds and releases (GitHub Actions)

VeloPilot comes as three apps, which install next to each other (and next to OsmAnd), each with its own F-Droid
repository on cdn.velopilot.be:

| App | Flavour | Built | GitHub release | F-Droid repository |
|---|---|---|---|---|
| VeloPilot Dev, `be.velopilot.dev` (α icon) | `velopilotDev` | every push to main | pre-release `vX.Y.Z-dev.RUN` | <https://cdn.velopilot.be/fdroid-dev/repo> |
| VeloPilot Beta, `be.velopilot.beta` (β icon) | `velopilotBeta` | by hand | pre-release `vX.Y.Z-beta.N` | <https://cdn.velopilot.be/fdroid-beta/repo> |
| VeloPilot, `be.velopilot.app` | `velopilot` | by hand | release `vX.Y.Z` | <https://cdn.velopilot.be/fdroid/repo> |

The workflows build release APKs (minified, signed) with `.github/actions/velopilot-apk` and publish each as a
GitHub release with exactly one APK; [velo-pilot/fdroid](https://github.com/velo-pilot/fdroid) turns those into
the F-Droid repositories. Version numbers are in `build-container/ci/version.sh`: VeloPilot has its own version,
independent of OsmAnd's. Releases `vMAJOR.MINOR.PATCH` get versionCode MAJOR × 10000 + MINOR × 100 + PATCH, betas
`vX.Y.Z-beta.N` of the next release that release's versionCode × 100 + N, dev builds the run number of the dev
workflow.

- **Dev builds**: `.github/workflows/velopilot-dev.yml` builds every push to main as `vX.Y.Z-dev.RUN` (X.Y.Z is
  the next release), with the subjects of the pushed commits as notes, keeps the newest 5 and asks velo-pilot/fdroid
  to update its dev repository.
- **Betas and releases**: run *Actions > VeloPilot release > Run workflow* on main and pick beta or release. The
  version defaults to the last release with its patch number one up (the first release takes the versionName of the
  velopilot flavour in `OsmAnd/build.gradle`), the notes to the subjects of the commits on main since the previous
  release or beta (F-Droid shows up to 500 characters). A beta goes to the beta repository right away; publish a
  release in the F-Droid release repository with `./release.sh` in velo-pilot/fdroid, which takes that repository's
  key out of Bitwarden.

The jobs run in the GitHub environments `dev` (dev signing key) and `release` (release signing key, for betas and
releases), which only main may use. Their secrets (`APK_KEYSTORE_BASE64`, `APK_KEY_PASSWORD`,
`FDROID_DISPATCH_TOKEN`) are managed by OpenTofu in velo-pilot/fdroid (`infra/`), where `infra/apk-key.sh` created
the keys. Release builds are signed with other keys than the local debug builds, so switching from a debug build to
a build from F-Droid means uninstalling the debug build first.
