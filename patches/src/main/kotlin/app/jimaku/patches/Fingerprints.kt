package app.jimaku.patches

import app.morphe.patcher.Fingerprint

// Copied from morphe-patches `patches/src/main/kotlin/app/morphe/patches/youtube/shared/Fingerprints.kt`
// (YouTubeActivityOnCreateFingerprint), commit f3acc10e2691ffc4db29c6a6a651ee61fb54c047.
internal object MainActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)
