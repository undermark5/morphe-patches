package app.morphe.patches.marcopolo.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * PlatformAudioOutput$Route.<init>(AudioDeviceInfo)
 *
 * Maps AudioDeviceInfo.getType() onto the RouteType enum. The LE Audio types
 * (26 BLE_HEADSET, 27 BLE_SPEAKER, 30 BLE_BROADCAST) are unhandled and fall
 * through to RouteType.OTHER.
 */
internal object RouteConstructorFingerprint : Fingerprint(
    definingClass = "Lco/happybits/hbmx/PlatformAudioOutput\$Route;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("Landroid/media/AudioDeviceInfo;"),
    filters = listOf(
        methodCall(smali = "Landroid/media/AudioDeviceInfo;->getType()I"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * PlatformAudioOutput.getAvailableRoutes()
 *
 * Enumerates output devices and builds the Route list that both the routing
 * logic and the route picker menu read from. The switch has no case for the
 * LE Audio types, so those devices are skipped entirely.
 */
internal object GetAvailableRoutesFingerprint : Fingerprint(
    definingClass = "Lco/happybits/hbmx/PlatformAudioOutput;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Landroid/media/AudioManager;->getDevices(I)[Landroid/media/AudioDeviceInfo;"),
        methodCall(smali = "Landroid/media/AudioDeviceInfo;->getType()I"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * PrivacyModeAudioRouter.updateForCurrentAudioRoutes(Route, RouteChangeReason)
 *
 * Matches the call to PlatformAudioOutput.hasBluetooth() at the top of this method.
 * hasBluetooth() just returns the private _hasBluetooth field, which has no setter
 * anywhere in the app's own Kotlin/Java code — it's written exclusively by the
 * native hbmx core, almost certainly from classic Bluetooth A2DP/HFP profile
 * connection callbacks. LE Audio devices never establish an A2DP or HFP profile
 * connection, so this stays false for an LE Audio-only pairing even while such a
 * device is present and selected as the active route.
 *
 * When hasBluetooth() is false, this method early-returns, forcing its
 * audioRouteConfiguration Property to NONE; ConversationHeaderController hides the
 * entire audio-route button whenever that configuration is NONE. So without
 * patching this call site too, the output/route-picker button never appears for an
 * LE Audio connection, regardless of the RouteType remap above.
 *
 * Not filtering by accessFlags/parameters/returnType: jadx reports this method's
 * declared access as changed from private (it's invoked from an anonymous
 * EventBus listener), so the real compiled access flags are uncertain. The method
 * name alone is unique enough in this class.
 */
internal object UpdateForCurrentAudioRoutesFingerprint : Fingerprint(
    definingClass = "Lco/happybits/marcopolo/ui/screens/conversation/PrivacyModeAudioRouter;",
    name = "updateForCurrentAudioRoutes",
    filters = listOf(
        methodCall(smali = "Lco/happybits/hbmx/PlatformAudioOutput;->hasBluetooth()Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * AudioRecorder.initialize(int, int, int)
 *
 * Brute-forces sample rate / channel / audio source combinations until an
 * AudioRecord constructs successfully, then stores it in the _record field.
 * The last filter is that field store: the point where the freshly built
 * recorder exists and `this` is in a known register.
 */
internal object AudioRecorderInitializeFingerprint : Fingerprint(
    definingClass = "Lco/happybits/marcopolo/video/recorder/AudioRecorder;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("I", "I", "I"),
    filters = listOf(
        methodCall(smali = "Landroid/media/AudioRecord;->getMinBufferSize(III)I"),
        methodCall(smali = "Landroid/media/AudioRecord;-><init>(IIIII)V"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/media/AudioRecord;",
            location = MatchAfterImmediately()
        )
    )
)
