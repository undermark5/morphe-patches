package app.morphe.patches.marcopolo.audio

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.marcopolo.shared.Constants.COMPATIBILITY_MARCO_POLO
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val AUDIO_RECORDER_CLASS =
    "Lco/happybits/marcopolo/video/recorder/AudioRecorder;"

private const val HELPER_METHOD_NAME = "patch_applyBleInputDevice"

/** MediaRecorder.AudioSource.MIC */
private const val AUDIO_SOURCE_MIC = 1

@Suppress("unused")
val bleAudioInputPatch = bytecodePatch(
    name = "Bluetooth LE Audio microphone",
    description = "Records from a Bluetooth LE Audio device's microphone instead of the " +
        "phone's built-in mic. Requires the Bluetooth LE Audio output patch to be useful.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MARCO_POLO)

    execute {
        AudioRecorderInitializeFingerprint.let { fingerprint ->

            // region Add the device-selection helper to AudioRecorder itself.
            //
            // Adding a method rather than shipping an extension keeps this a single-file
            // patch, and more importantly gives the lookup its own register frame.
            // initialize() is register-starved (it uses v0-v21 with /range invokes), so
            // there is no safe scratch space to do an array walk inline.
            //
            // Mirrors the documented recording recipe:
            // developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording
            //   getDevices(GET_DEVICES_INPUTS) -> find TYPE_BLE_HEADSET -> setPreferredDevice
            fingerprint.classDef.methods.add(
                ImmutableMethod(
                    AUDIO_RECORDER_CLASS,
                    HELPER_METHOD_NAME,
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    // v0-v5 locals, p0 -> v6.
                    MutableMethodImplementation(7),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            iget-object v0, p0, $AUDIO_RECORDER_CLASS->_record:Landroid/media/AudioRecord;
                            if-eqz v0, :patch_done
                            iget-object v1, p0, $AUDIO_RECORDER_CLASS->_audioManager:Landroid/media/AudioManager;
                            if-eqz v1, :patch_done

                            # GET_DEVICES_INPUTS
                            const/4 v2, 0x1
                            invoke-virtual { v1, v2 }, Landroid/media/AudioManager;->getDevices(I)[Landroid/media/AudioDeviceInfo;
                            move-result-object v1

                            array-length v2, v1
                            const/4 v3, 0x0

                            :patch_loop
                            if-ge v3, v2, :patch_done
                            aget-object v4, v1, v3
                            invoke-virtual { v4 }, Landroid/media/AudioDeviceInfo;->getType()I
                            move-result v5

                            # Compare against TYPE_BLE_HEADSET (26) without burning a register
                            # on the constant.
                            add-int/lit8 v5, v5, -0x1a
                            if-nez v5, :patch_next

                            invoke-virtual { v0, v4 }, Landroid/media/AudioRecord;->setPreferredDevice(Landroid/media/AudioDeviceInfo;)Z
                            goto :patch_done

                            :patch_next
                            add-int/lit8 v3, v3, 0x1
                            goto :patch_loop

                            :patch_done
                            return-void
                        """
                    )
                }
            )
            // endregion

            // region Put MIC first in the audio source preference list.
            //
            // initialize() tries sources in the order {CAMCORDER, MIC, DEFAULT} and keeps
            // the first that constructs. CAMCORDER almost always constructs, so it always
            // wins — and it is tuned to the camera-facing mic array, which will not follow
            // a preferred Bluetooth device. The recording guide specifies MIC.
            //
            // The list is built by a filled-new-array over three registers holding those
            // constants, so reorder the operands rather than assuming which register is which.
            fingerprint.method.apply {
                val instructions = implementation!!.instructions.toList()

                val arrayIndex = instructions.indexOfFirst {
                    it.opcode == Opcode.FILLED_NEW_ARRAY &&
                        (it as? ReferenceInstruction)?.reference?.toString() == "[I" &&
                        (it as Instruction35c).registerCount == 3
                }
                if (arrayIndex < 0) throw PatchException("Audio source array not found")

                val arrayInstruction = getInstruction<Instruction35c>(arrayIndex)
                val operands = listOf(
                    arrayInstruction.registerC,
                    arrayInstruction.registerD,
                    arrayInstruction.registerE
                )

                // Walk back to the const that last wrote each operand register.
                fun literalOf(register: Int): Int? {
                    for (i in arrayIndex - 1 downTo 0) {
                        val instruction = instructions[i]
                        if (instruction is OneRegisterInstruction &&
                            instruction.registerA == register
                        ) {
                            return (instruction as? NarrowLiteralInstruction)?.narrowLiteral
                        }
                    }
                    return null
                }

                val micRegister = operands.firstOrNull { literalOf(it) == AUDIO_SOURCE_MIC }
                    ?: throw PatchException("Could not identify the MIC audio source register")

                if (operands.first() != micRegister) {
                    val reordered = listOf(micRegister) + operands.filterNot { it == micRegister }
                    replaceInstruction(
                        arrayIndex,
                        "filled-new-array { ${reordered.joinToString(", ") { "v$it" }} }, [I"
                    )
                }
            }
            // endregion

            // region Call the helper once the AudioRecord has been stored.
            //
            // Injecting after the field store means the helper can read _record itself,
            // so the call needs only the register already holding `this` — no scratch
            // register in a method that has none to spare.
            val fieldStoreIndex = fingerprint.instructionMatches.last().index
            val thisRegister = fingerprint.method
                .getInstruction<TwoRegisterInstruction>(fieldStoreIndex).registerB

            fingerprint.method.addInstructions(
                fieldStoreIndex + 1,
                "invoke-virtual { v$thisRegister }, $AUDIO_RECORDER_CLASS->$HELPER_METHOD_NAME()V"
            )
            // endregion
        }
    }
}
