# Jimaku patches

[Jimaku](https://www.jimaku.app) subtitles in the YouTube Android app, as a patch bundle for use with Morphe.

## About

Jimaku makes subtitles for YouTube videos and translates them into your language. This bundle shows them over the YouTube app's player, in the app's language, for videos Jimaku already has subtitles for.

These are early pre-releases. They show subtitles that are already in Jimaku's cache, with no menu or setting yet. The player menu, making subtitles, and signing in come in later releases.

### How to use these patches

The Jimaku patch adds only Jimaku. Apply it together with Morphe's YouTube patches, in one run:

1. In Morphe Manager, add this source with the button below, or add the repository link by hand: https://github.com/1-Felix/jimaku-patches
2. Turn on **Pre-release patches** for this source. Until the first stable release, every release is a pre-release.
3. Turn on **Expert mode** in Manager's settings. Simple mode patches each app from one source only.
4. Patch YouTube with both sources selected: Morphe's patches and Jimaku. Use a YouTube version listed below, and keep Morphe's default patches on: Jimaku attaches to the player through them. If they are missing, patching stops with a message saying so.

<a href="https://morphe.software/add-source?github=1-Felix/jimaku-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

### Signed releases

Every release is signed with this bundle's own key, whose public half is [morphe-signing.pub](morphe-signing.pub). Once you have added the source, Morphe Manager refuses a release that wasn't signed with it. If the key ever has to change, the release notes say so, and Manager asks you to confirm the new key.

### What the patch sends

To find subtitles, the app asks `api.jimaku.app` for the video's ID and your language. Requests carry no YouTube account data, no cookies and no credentials. Their User-Agent names only Jimaku and the bundle's version.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.2](https://github.com/1-Felix/jimaku-patches/releases/tag/v1.0.0-dev.2)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Package name:** `com.google.android.youtube`

**🎯 Supported versions:**

| 21.16.256 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Jimaku](#jimaku) | Shows Jimaku subtitles over the player. Apply together with Morphe's YouTube patches. |  |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Morphe's build plugin comes from GitHub Packages, so Gradle needs a GitHub token with `read:packages`, passed as `GITHUB_ACTOR` and `GITHUB_TOKEN`.
- Run `./gradlew buildAndroid`.
- The built patches file is `patches/build/libs/patches-*.mpp`. Apply it with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop), or add it to Morphe Manager as a local source, together with Morphe's YouTube patches.

## 📜 License

Jimaku patches are licensed under the [GNU General Public License v3.0](LICENSE), with the additional terms in [NOTICE](NOTICE). They are built from the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template) and are not made by the Morphe project.
