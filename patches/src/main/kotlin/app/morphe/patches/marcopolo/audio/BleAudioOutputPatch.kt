package app.morphe.patches.marcopolo.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.marcopolo.shared.Constants.COMPATIBILITY_MARCO_POLO
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val bleAudioOutputPatch = bytecodePatch(
    name = "Bluetooth LE Audio output",
    description = "Lets Marco Polo route playback to Bluetooth LE Audio devices " +
        "(hearing aids, LE Audio earbuds) instead of falling back to the phone speaker.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MARCO_POLO)

    execute {
        /**
         * Rewrites the AudioDeviceInfo type in-place, immediately after the app reads it.
         *
         * TYPE_BLE_HEADSET (26), TYPE_BLE_SPEAKER (27) and TYPE_BLE_BROADCAST (30)
         * become TYPE_WIRED_HEADSET (3). Every other type is left untouched.
         *
         * Type 3 is deliberate: it is added to the route list unconditionally, it maps
         * to RouteType.HEADPHONES which outranks earpiece and speaker in the selection
         * chain, and it takes the non-HFP branch in applyOutputRoute — so the app calls
         * AudioTrack.setPreferredDevice(bleDevice) instead of startBluetoothSco(),
         * which is what LE Audio actually needs.
         *
         * Mapping to TYPE_BLUETOOTH_A2DP (8) would look more natural but is gated on
         * `_enableBluetooth && !_enableBluetoothHfp`, and _enableBluetoothHfp is driven
         * by the server-side `bluetoothHfpAndroid` feature flag. If that flag flipped on
         * for your account, BLE devices would silently vanish from the list again.
         *
         * The sequence only ever touches the destination register of the move-result,
         * so it needs no scratch register and cannot clobber live values.
         */
        fun Fingerprint.normalizeBleDeviceType() {
            val moveResultIndex = instructionMatches.last().index

            method.apply {
                val register = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                addInstructionsWithLabels(
                    moveResultIndex + 1,
                    """
                        add-int/lit8 v$register, v$register, -0x1a
                        if-eqz v$register, :morphe_is_ble
                        add-int/lit8 v$register, v$register, -0x1
                        if-eqz v$register, :morphe_is_ble
                        add-int/lit8 v$register, v$register, -0x3
                        if-eqz v$register, :morphe_is_ble
                        add-int/lit8 v$register, v$register, 0x1e
                        goto :morphe_done
                        :morphe_is_ble
                        const/16 v$register, 0x3
                        :morphe_done
                        nop
                    """
                )
            }
        }

        // Makes the device selectable and gives it a usable RouteType.
        GetAvailableRoutesFingerprint.normalizeBleDeviceType()

        // Makes Route report the right type once it has been selected.
        RouteConstructorFingerprint.normalizeBleDeviceType()
    }
}
