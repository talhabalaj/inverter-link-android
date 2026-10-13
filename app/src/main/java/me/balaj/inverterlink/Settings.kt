package me.balaj.inverterlink

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

enum class Category(val title: String) {
    OUTPUT("Output"),
    BATTERY("Battery"),
    GENERAL("General"),
    LED("LED"),
}

data class Choice(val value: String, val label: String)

sealed interface Source {
    data class Rating(val index: Int) : Source

    data class Equalization(val index: Int) : Source

    data class Flag(val letter: Char) : Source

    data class LED(val key: String, val slot: Int = 0) : Source
}

sealed interface Input {
    data class Choices(val prefix: String, val width: Int, val values: List<Choice>) : Input

    data class Currents(val prefix: String, val utility: Boolean) : Input

    data class Integer(
        val prefix: String,
        val width: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
    ) : Input

    data class Decimal(val prefix: String, val min: Double, val max: Double, val places: Int = 1) :
        Input

    data class Flag(val letter: Char) : Input

    data class Color(val slot: Int) : Input
}

data class InverterSettings(
    val ratings: List<String>,
    val enabled: Set<Char>,
    val disabled: Set<Char>,
    val equalization: List<String>,
    val led: LEDSettings,
    val maxCharging: List<Int>,
    val utilityCharging: List<Int>,
) {
    fun raw(source: Source): String? =
        when (source) {
            is Source.Rating -> ratings.getOrNull(source.index)
            is Source.Equalization -> equalization.getOrNull(source.index)
            is Source.Flag ->
                if (source.letter in enabled) "1" else if (source.letter in disabled) "0" else null
            is Source.LED ->
                when (source.key) {
                    "enabled" -> if (led.enabled) "1" else "0"
                    "speed" -> led.speed.toString()
                    "effect" -> led.effect.toString()
                    "brightness" -> led.brightness.toString()
                    "count" -> led.count.toString()
                    "color" ->
                        if (source.slot <= led.count) led.colors.getOrNull(source.slot - 1)
                        else null
                    else -> null
                }
        }

    companion object {
        fun parse(
            ratings: String,
            flags: String,
            equalization: String,
            led: String,
            max: String,
            utility: String,
        ): InverterSettings {
            fun fields(v: String) = v.trim().split(Regex("\\s+"))
            val r = fields(ratings)
            val e = fields(equalization)
            val d = flags.indexOf('D')
            checkProtocol(
                r.size >= 25 &&
                    e.size == 10 &&
                    flags.startsWith('E') &&
                    d > 0 &&
                    flags.count { it == 'E' || it == 'D' } == 2,
                "Unsupported inverter settings layout.",
            )
            val enabled = flags.substring(1, d).toSet()
            val disabled = flags.substring(d + 1).toSet()
            checkProtocol(
                enabled.intersect(disabled).isEmpty() &&
                    (enabled + disabled).all { it in 'a'..'z' },
                "Invalid inverter flag settings.",
            )
            fun currents(v: String) =
                fields(v).map {
                    it.toIntOrNull()?.takeIf { n -> n in 0..999 }
                        ?: error("Invalid charging choices.")
                }
            return InverterSettings(
                r,
                enabled,
                disabled,
                e,
                LEDSettings.parse(led),
                currents(max),
                currents(utility),
            )
        }
    }
}

data class Setting(
    val id: String,
    val title: String,
    val category: Category,
    val source: Source,
    val input: Input,
) {
    val batteryCritical
        get() = category == Category.BATTERY

    val unit
        get() =
            when (input) {
                is Input.Decimal -> "V"
                is Input.Currents -> "A"
                is Input.Integer ->
                    when (id) {
                        "equalization_time",
                        "equalization_timeout" -> "min"
                        "equalization_period" -> "days"
                        else -> null
                    }
                else -> null
            }

    fun choices(settings: InverterSettings): List<Choice>? =
        when (val i = input) {
            is Input.Choices -> i.values
            is Input.Currents ->
                (if (i.utility) settings.utilityCharging else settings.maxCharging).map {
                    Choice(it.toString(), "$it A")
                }
            is Input.Flag -> listOf(Choice("0", "Off"), Choice("1", "On"))
            else -> null
        }

    fun command(value: String, settings: InverterSettings): String {
        checkProtocol(
            settings.raw(source) != null,
            "This setting is not available on this inverter.",
        )
        return when (val i = input) {
            is Input.Choices -> {
                checkProtocol(
                    i.values.any { it.value == value },
                    "Choose a listed value for $title.",
                )
                i.prefix + "%0${i.width}d".format(Locale.ROOT, value.toInt())
            }
            is Input.Currents -> {
                val n = value.toIntOrNull()
                checkProtocol(
                    n != null &&
                        n in if (i.utility) settings.utilityCharging else settings.maxCharging,
                    "Choose a current offered by the inverter.",
                )
                i.prefix + "%03d".format(Locale.ROOT, n)
            }
            is Input.Integer -> {
                val n = value.toIntOrNull()
                checkProtocol(
                    n != null && n in i.min..i.max && (n - i.min) % i.step == 0,
                    "Enter ${i.min}–${i.max} in steps of ${i.step}.",
                )
                i.prefix + "%0${i.width}d".format(Locale.ROOT, n)
            }
            is Input.Decimal -> {
                val n = value.toDoubleOrNull()
                val scale = 10.0.pow(i.places)
                checkProtocol(
                    n != null &&
                        n.isFinite() &&
                        n >= i.min &&
                        n <= i.max &&
                        abs(round(n * scale) - n * scale) < 0.00001,
                    "Enter ${i.min}–${i.max} with up to ${i.places} decimal places.",
                )
                i.prefix + "%0${if(i.places==1)4 else 5}.${i.places}f".format(Locale.ROOT, n)
            }
            is Input.Flag -> {
                checkProtocol(value == "0" || value == "1", "Choose On or Off.")
                "P${if(value=="1")"E" else "D"}${i.letter}"
            }
            is Input.Color -> {
                val hex = value.trim().removePrefix("#").uppercase(Locale.ROOT)
                checkProtocol(
                    hex.length == 6 && hex.all { it in '0'..'9' || it in 'A'..'F' },
                    "Enter six-digit RGB hex.",
                )
                "PLEDC${i.slot}" +
                    hex.chunked(2).joinToString("") { "%03d".format(Locale.ROOT, it.toInt(16)) }
            }
        }
    }

    fun verifies(requested: String, after: InverterSettings): Boolean {
        val actual = after.raw(source) ?: return false
        return when (input) {
            is Input.Color -> actual.equals(requested.trim().removePrefix("#"), true)
            is Input.Decimal ->
                abs(
                    (actual.toDoubleOrNull() ?: return false) -
                        (requested.toDoubleOrNull() ?: return false)
                ) < 0.001
            else ->
                actual.toDoubleOrNull() != null &&
                    actual.toDoubleOrNull() == requested.toDoubleOrNull()
        }
    }

    fun display(raw: String?, settings: InverterSettings): String {
        if (raw == null)
            return if (source == Source.LED("color", 3) && settings.led.count == 2) "Two-color mode"
            else "Unavailable"
        if (input is Input.Color) return "#$raw"
        choices(settings)
            ?.firstOrNull { it.value.toDoubleOrNull() == raw.toDoubleOrNull() }
            ?.let {
                return it.label
            }
        if (input is Input.Decimal)
            return "% .${input.places}f V"
                .format(Locale.ROOT, raw.toDoubleOrNull() ?: return raw)
                .trim()
        if (input is Input.Integer)
            return if (id == "led_brightness")
                if (raw.toIntOrNull() in 1..9) "$raw / 9" else "Reported: $raw"
            else "$raw${unit?.let { " $it" }.orEmpty()}"
        return raw
    }

    val hint
        get() =
            when (val i = input) {
                is Input.Integer ->
                    "${i.min}–${i.max}${unit?.let {" $it"}.orEmpty()} · step ${i.step}"
                is Input.Decimal -> "${i.min}–${i.max} V · ${i.places} decimal place(s)"
                else -> null
            }

    companion object {
        val all: List<Setting> = buildList {
            fun choices(
                id: String,
                title: String,
                c: Category,
                s: Source,
                p: String,
                w: Int,
                vararg options: Pair<String, String>,
            ) {
                add(
                    Setting(
                        id,
                        title,
                        c,
                        s,
                        Input.Choices(p, w, options.map { Choice(it.first, it.second) }),
                    )
                )
            }
            fun decimal(
                id: String,
                title: String,
                s: Source,
                p: String,
                min: Double,
                max: Double,
                places: Int = 1,
            ) {
                add(Setting(id, title, Category.BATTERY, s, Input.Decimal(p, min, max, places)))
            }
            fun integer(
                id: String,
                title: String,
                s: Source,
                p: String,
                min: Int,
                max: Int,
                step: Int = 1,
            ) {
                add(Setting(id, title, Category.BATTERY, s, Input.Integer(p, 3, min, max, step)))
            }
            choices(
                "output_source",
                "Output source priority",
                Category.OUTPUT,
                Source.Rating(16),
                "POP",
                2,
                "0" to "Utility first",
                "1" to "Solar first",
                "2" to "Solar → battery → utility",
            )
            choices(
                "input_range",
                "AC input range",
                Category.OUTPUT,
                Source.Rating(15),
                "PGR",
                2,
                "0" to "Appliance",
                "1" to "UPS",
            )
            choices(
                "output_voltage",
                "Output voltage",
                Category.OUTPUT,
                Source.Rating(2),
                "V",
                3,
                "220" to "220 V",
                "230" to "230 V",
                "240" to "240 V",
            )
            choices(
                "output_frequency",
                "Output frequency",
                Category.OUTPUT,
                Source.Rating(3),
                "F",
                2,
                "50" to "50 Hz",
                "60" to "60 Hz",
            )
            choices(
                "battery_type",
                "Battery type",
                Category.BATTERY,
                Source.Rating(12),
                "PBT",
                2,
                *listOf(
                        "AGM",
                        "Flooded",
                        "User defined",
                        "Pylontech",
                        "Shinheung",
                        "Weco",
                        "Soltaro",
                        "BAK",
                        "Lib",
                        "Lic",
                    )
                    .mapIndexed { index, label -> index.toString() to label }
                    .toTypedArray(),
            )
            decimal("cutoff", "Battery cut-off voltage", Source.Rating(9), "PSDV", 21.0, 24.0)
            decimal("bulk", "Bulk charging voltage", Source.Rating(10), "PCVV", 25.0, 31.5)
            decimal("float", "Float charging voltage", Source.Rating(11), "PBFT", 25.0, 31.5)
            add(
                Setting(
                    "max_charge",
                    "Max charging current",
                    Category.BATTERY,
                    Source.Rating(14),
                    Input.Currents("MNCHGC", false),
                )
            )
            add(
                Setting(
                    "max_ac_charge",
                    "Max AC charging current",
                    Category.BATTERY,
                    Source.Rating(13),
                    Input.Currents("MUCHGC", true),
                )
            )
            choices(
                "charge_source",
                "Charging source priority",
                Category.BATTERY,
                Source.Rating(17),
                "PCP",
                2,
                "1" to "Solar first",
                "2" to "Solar + utility",
                "3" to "Solar only",
            )
            decimal("back_to_grid", "Back to grid voltage", Source.Rating(8), "PBCV", 21.0, 25.5)
            decimal(
                "back_to_battery",
                "Back to battery voltage",
                Source.Rating(22),
                "PBDV",
                24.0,
                29.0,
            )
            choices(
                "equalization",
                "Battery equalization",
                Category.BATTERY,
                Source.Equalization(0),
                "PBEQE",
                1,
                "0" to "Off",
                "1" to "On",
            )
            choices(
                "equalization_now",
                "Equalize now",
                Category.BATTERY,
                Source.Equalization(8),
                "PBEQA",
                1,
                "0" to "Off",
                "1" to "On",
            )
            integer(
                "equalization_time",
                "Equalization time",
                Source.Equalization(1),
                "PBEQT",
                5,
                900,
                5,
            )
            integer(
                "equalization_timeout",
                "Equalization timeout",
                Source.Equalization(7),
                "PBEQOT",
                5,
                900,
                5,
            )
            integer(
                "equalization_period",
                "Equalization period",
                Source.Equalization(2),
                "PBEQP",
                0,
                90,
            )
            decimal(
                "equalization_voltage",
                "Equalization voltage",
                Source.Equalization(5),
                "PBEQV",
                25.0,
                31.5,
                2,
            )
            listOf(
                    'a' to "Buzzer",
                    'b' to "Overload bypass",
                    'd' to "Solar feed to grid",
                    'j' to "Power saving",
                    'k' to "LCD auto-return",
                    'u' to "Overload auto-restart",
                    'v' to "Temperature auto-restart",
                    'x' to "Backlight",
                    'y' to "Primary-source alarm",
                    'z' to "Fault code record",
                )
                .forEach { (letter, title) ->
                    add(
                        Setting(
                            "flag_$letter",
                            title,
                            Category.GENERAL,
                            Source.Flag(letter),
                            Input.Flag(letter),
                        )
                    )
                }
            choices(
                "led_status",
                "LED status",
                Category.LED,
                Source.LED("enabled"),
                "PLEDE",
                1,
                "0" to "Off",
                "1" to "On",
            )
            choices(
                "led_speed",
                "LED speed",
                Category.LED,
                Source.LED("speed"),
                "PLEDS",
                1,
                "0" to "Slow",
                "1" to "Medium",
                "2" to "Fast",
            )
            choices(
                "led_effect",
                "LED effect",
                Category.LED,
                Source.LED("effect"),
                "PLEDM",
                1,
                "0" to "Breathing",
                "2" to "Solid",
                "3" to "Scrolling",
            )
            add(
                Setting(
                    "led_brightness",
                    "LED brightness",
                    Category.LED,
                    Source.LED("brightness"),
                    Input.Integer("PLEDB", 1, 1, 9),
                )
            )
            choices(
                "led_count",
                "LED color count",
                Category.LED,
                Source.LED("count"),
                "PLEDT",
                1,
                "2" to "Two",
                "3" to "Three",
            )
            listOf("line", "AVR", "battery").forEachIndexed { index, label ->
                add(
                    Setting(
                        "led_color_${index+1}",
                        "LED color ${index+1} · $label",
                        Category.LED,
                        Source.LED("color", index + 1),
                        Input.Color(index + 1),
                    )
                )
            }
        }
    }
}
