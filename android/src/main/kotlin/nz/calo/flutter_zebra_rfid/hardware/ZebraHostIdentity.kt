package nz.calo.flutter_zebra_rfid.hardware

import android.os.Build

internal data class ZebraHostIdentity(
    val manufacturer: String,
    val model: String,
    val product: String,
    val device: String,
) {
    private val normalizedValues: List<String>
        get() = listOf(manufacturer, model, product, device).map { it.trim().uppercase() }

    val isZebraTerminal: Boolean
        get() = normalizedValues.any { value ->
            value.contains("ZEBRA") || value.contains("MOTOROLA")
        }

    val isEm45: Boolean
        get() = normalizedValues.any { it.contains("EM45") }

    val summary: String
        get() = "manufacturer=$manufacturer model=$model product=$product device=$device"
}

internal fun currentZebraHostIdentity(): ZebraHostIdentity = ZebraHostIdentity(
    manufacturer = Build.MANUFACTURER.orEmpty(),
    model = Build.MODEL.orEmpty(),
    product = Build.PRODUCT.orEmpty(),
    device = Build.DEVICE.orEmpty(),
)
