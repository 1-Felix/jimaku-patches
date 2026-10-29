package app.jimaku.patches

import app.jimaku.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/jimaku/extension/JimakuPatch;"

@Suppress("unused")
val jimakuPatch = bytecodePatch(
    name = "Jimaku",
    description = "Shows Jimaku subtitles over the player. Apply together with Morphe's YouTube patches.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    extendWith("extensions/jimaku.mpe")

    execute {
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onMainActivityCreate(Landroid/app/Activity;)V",
        )
    }
}
