package app.morphe.patches.marcopolo.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_MARCO_POLO = Compatibility(
        name = "Marco Polo",
        packageName = "co.happybits.marcopolo",
        // No `signatures` set on purpose: an antisplit-merged APK is re-signed,
        // so pinning the Play Store signature would reject your own input file.
        targets = listOf(
            AppTarget(
                version = "0.590.0",
                minSdk = 32
            )
        )
    )
}
