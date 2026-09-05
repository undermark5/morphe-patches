package app.morphe.patches.marcopolo.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.marcopolo.shared.Constants.COMPATIBILITY_MARCO_POLO
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PLATFORM_AUDIO_OUTPUT_CLASS = "Lco/happybits/hbmx/PlatformAudioOutput;"
private const val HAS_BLE_DEVICE_HELPER_NAME = "patch_hasBleAudioDevice"

/** AudioDeviceInfo.TYPE_WIRED_HEADSET */
private const val REMAP_TARGET_WIRED_HEADSET = 0x3
/** AudioDeviceInfo.TYPE_BLUETOOTH_A2DP */
private const val REMAP_TARGET_BLUETOOTH_A2DP = 0x8

@Suppress("unused")
val bleAudioOutputPatch = bytecodePatch(
    name = "Bluetooth LE Audio output",
    description = "Lets Marco Polo route playback to Bluetooth LE Audio devices " +
        "(hearing aids, LE Audio earbuds) instead of falling back to the phone speaker.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MARCO_POLO)

    val labelAsBluetooth = booleanOption(
        key = "labelAsBluetooth",
        default = false,
        title = "Show as Bluetooth instead of wired headphones",
        description = "By default the LE Audio device is disguised as a wired headset " +
            "(TYPE_WIRED_HEADSET), which is always safe: it is unconditionally added to " +
            "the route list and never depends on any server-side flag. Enabling this " +
            "instead disguises it as TYPE_BLUETOOTH_A2DP, so it shows up correctly " +
            "labeled as \"Bluetooth\" and gets the Bluetooth-highlighted route button " +
            "state. Only enable this if Marco Polo's server-side \"bluetoothHfpAndroid\" " +
            "feature flag is off for your account — that flag is controlled remotely by " +
            "Marco Polo, not by you, and if it is (or becomes) enabled, devices disguised " +
            "as Bluetooth are silently dropped from the route list entirely rather than " +
            "merely mislabeled."
    )

    execute {
        /**
         * Rewrites the AudioDeviceInfo type in-place, immediately after the app reads it.
         *
         * TYPE_BLE_HEADSET (26), TYPE_BLE_SPEAKER (27) and TYPE_BLE_BROADCAST (30) become
         * [targetType]. Every other type is left untouched.
         *
         * The default target, TYPE_WIRED_HEADSET (3), is deliberate: it is added to the
         * route list unconditionally, it maps to RouteType.HEADPHONES which outranks
         * earpiece and speaker in the selection chain, and it takes the non-HFP branch in
         * applyOutputRoute — so the app calls AudioTrack.setPreferredDevice(bleDevice)
         * instead of startBluetoothSco(), which is what LE Audio actually needs.
         *
         * TYPE_BLUETOOTH_A2DP (8) looks more natural (and is what the [labelAsBluetooth]
         * option selects) but is gated on `_enableBluetooth && !_enableBluetoothHfp`, and
         * _enableBluetoothHfp is driven by the server-side `bluetoothHfpAndroid` feature
         * flag. If that flag is or becomes enabled, BLE devices silently vanish from the
         * list entirely under this target, instead of merely being mislabeled.
         *
         * The sequence only ever touches the destination register of the move-result,
         * so it needs no scratch register and cannot clobber live values.
         */
        fun Fingerprint.normalizeBleDeviceType(targetType: Int) {
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
                        const/16 v$register, $targetType
                        :morphe_done
                        nop
                    """
                )
            }
        }

        val targetType = if (labelAsBluetooth.value == true) {
            REMAP_TARGET_BLUETOOTH_A2DP
        } else {
            REMAP_TARGET_WIRED_HEADSET
        }

        // Makes the device selectable and gives it a usable RouteType.
        GetAvailableRoutesFingerprint.normalizeBleDeviceType(targetType)

        // Makes Route report the right type once it has been selected.
        RouteConstructorFingerprint.normalizeBleDeviceType(targetType)

        /**
         * Makes the audio-route button (and its picker menu) actually appear for an LE
         * Audio connection.
         *
         * PlatformAudioOutput.hasBluetooth() just returns the private _hasBluetooth
         * field, which has no setter anywhere in the app's own code — it's written
         * exclusively by the native hbmx core, almost certainly from classic Bluetooth
         * A2DP/HFP profile connection callbacks. LE Audio devices never establish an
         * A2DP or HFP profile connection, so this stays false for an LE Audio-only
         * pairing.
         *
         * PrivacyModeAudioRouter.updateForCurrentAudioRoutes() early-returns whenever
         * that call returns false, forcing its audioRouteConfiguration Property to
         * NONE, and ConversationHeaderController hides the entire audio-route button
         * whenever that configuration is NONE. So without this, the remap above is
         * invisible: audio still routes correctly, but the button/menu never appears.
         *
         * Patching the call site here (rather than hasBluetooth() itself) means
         * hasBluetooth()'s own tiny body is left alone, so there's no need to guess
         * at its real register budget. Classic Bluetooth behavior (the native flag)
         * is unaffected: this only ever adds a new `true` case, never removes one.
         *
         * The move-result's destination register is reused for the whole fix (same
         * trick as the RouteType remap above), but *not* the original call's object
         * register: verified against the real compiled output that D8 reuses that
         * same register as the move-result destination here (`iget-object p2, ...
         * _audioOut; invoke-virtual {p2}, hasBluetooth()Z; move-result p2`), so by
         * the time this code runs, that register holds a boolean, not the
         * PlatformAudioOutput reference — invoking a virtual method on it would be
         * invalid bytecode. The fix re-reads the _audioOut field directly instead.
         */
        UpdateForCurrentAudioRoutesFingerprint.let { fingerprint ->
            // region Add an LE Audio device-scan helper to PlatformAudioOutput.
            //
            // Lives on PlatformAudioOutput (not PrivacyModeAudioRouter) so it can call
            // the class's own private getAudioManager() directly (invoke-direct, legal
            // for a same-class private method) instead of duplicating its lazy
            // AudioManager initialization. Must be public: it is called cross-package
            // from PrivacyModeAudioRouter below.
            GetAvailableRoutesFingerprint.classDef.methods.add(
                ImmutableMethod(
                    PLATFORM_AUDIO_OUTPUT_CLASS,
                    HAS_BLE_DEVICE_HELPER_NAME,
                    listOf(),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    // v0-v4 locals, p0 -> v5.
                    MutableMethodImplementation(6),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-direct { p0 }, $PLATFORM_AUDIO_OUTPUT_CLASS->getAudioManager()Landroid/media/AudioManager;
                            move-result-object v0

                            # GET_DEVICES_OUTPUTS
                            const/4 v1, 0x2
                            invoke-virtual { v0, v1 }, Landroid/media/AudioManager;->getDevices(I)[Landroid/media/AudioDeviceInfo;
                            move-result-object v0

                            array-length v1, v0
                            const/4 v2, 0x0

                            :patch_loop
                            if-ge v2, v1, :patch_not_found
                            aget-object v3, v0, v2
                            invoke-virtual { v3 }, Landroid/media/AudioDeviceInfo;->getType()I
                            move-result v4

                            # TYPE_BLE_HEADSET (26), TYPE_BLE_SPEAKER (27), TYPE_BLE_BROADCAST (30).
                            add-int/lit8 v4, v4, -0x1a
                            if-eqz v4, :patch_found
                            add-int/lit8 v4, v4, -0x1
                            if-eqz v4, :patch_found
                            add-int/lit8 v4, v4, -0x3
                            if-eqz v4, :patch_found

                            add-int/lit8 v2, v2, 0x1
                            goto :patch_loop

                            :patch_found
                            const/4 v0, 0x1
                            return v0

                            :patch_not_found
                            const/4 v0, 0x0
                            return v0
                        """
                    )
                }
            )
            // endregion

            // region OR the helper's result into hasBluetooth()'s return value at the call site.
            //
            // Re-reads PrivacyModeAudioRouter's own _audioOut field rather than reusing
            // the original call's object register — see the class doc above for why
            // that register cannot be assumed to still hold the object reference here.
            val moveResultIndex = fingerprint.instructionMatches.last().index

            val resultRegister = fingerprint.method
                .getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            fingerprint.method.addInstructionsWithLabels(
                moveResultIndex + 1,
                """
                    if-nez v$resultRegister, :patch_done
                    iget-object v$resultRegister, p0, Lco/happybits/marcopolo/ui/screens/conversation/PrivacyModeAudioRouter;->_audioOut:$PLATFORM_AUDIO_OUTPUT_CLASS
                    invoke-virtual { v$resultRegister }, $PLATFORM_AUDIO_OUTPUT_CLASS->$HAS_BLE_DEVICE_HELPER_NAME()Z
                    move-result v$resultRegister
                    :patch_done
                    nop
                """
            )
            // endregion
        }
    }
}
