package app.jimaku.patches

import app.jimaku.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val jimakuPatch = bytecodePatch(
    name = "Jimaku",
    description = "Shows Jimaku subtitles over the player. Apply together with Morphe's YouTube patches.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
    }
}
