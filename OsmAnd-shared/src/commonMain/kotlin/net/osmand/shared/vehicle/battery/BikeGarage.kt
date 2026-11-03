package net.osmand.shared.vehicle.battery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A battery the user owns, e.g. "Bosch PowerTube 625". Batteries are kept in one list and each bike refers to
 * the ones it uses, so a battery that fits several bikes is entered once.
 */
@Serializable
data class BikeBattery(
	val id: String,
	val name: String,
	val capacityWh: Double
)

/**
 * One of the user's bikes. Several bikes can share a type: [profileKey] is the app profile (bike type) it is
 * ridden with, e.g. "speed_pedelec". A bike can be used in several ways ([usages]: "travel", "leisure", "sport");
 * [usage] is the way it is ridden now.
 */
@Serializable
data class Bike(
	val id: String,
	val name: String,
	val profileKey: String,
	val usage: String = USAGE_TRAVEL,
	val batteryIds: List<String> = emptyList(),
	/** Wh/km on flat ground; null = the default of the bike type */
	val consumptionWhPerKm: Double? = null,
	/** Symbol (drawable name) shown for the bike; null = the icon of its type */
	val iconName: String? = null,
	/** ARGB colour of the bike's symbol; null = the colour of its profile */
	val color: Int? = null,
	/** All ways this bike is used; empty for bikes saved when a bike had one, see [allUsages] */
	val usages: List<String> = emptyList()
) {
	/** The ways this bike is used, never empty and always with the current [usage]. */
	val allUsages: List<String>
		get() = if (usage in usages) usages else usages + usage

	/** Sets the ways of use, keeping the current one if it stays. [list] empty: nothing changes. */
	fun withUsages(list: List<String>): Bike {
		val distinct = list.distinct()
		return when {
			distinct.isEmpty() -> this
			usage in distinct -> copy(usages = distinct)
			else -> copy(usages = distinct, usage = distinct.first())
		}
	}

	companion object {
		const val USAGE_TRAVEL = "travel"
		const val USAGE_LEISURE = "leisure"
		const val USAGE_SPORT = "sport"
	}
}

/** All the user's bikes and batteries. Immutable: changes return a new garage. */
@Serializable
data class BikeGarage(
	val bikes: List<Bike> = emptyList(),
	val batteries: List<BikeBattery> = emptyList()
) {
	fun getBike(id: String?): Bike? = bikes.firstOrNull { it.id == id }

	fun getBattery(id: String?): BikeBattery? = batteries.firstOrNull { it.id == id }

	/** Batteries of [bike] in its order; ids of deleted batteries are skipped. */
	fun batteriesOf(bike: Bike): List<BikeBattery> = bike.batteryIds.mapNotNull { getBattery(it) }

	fun bikesOfProfile(profileKey: String): List<Bike> = bikes.filter { it.profileKey == profileKey }

	fun bikesUsing(battery: BikeBattery): List<Bike> = bikes.filter { battery.id in it.batteryIds }

	/**
	 * The bike ridden with [profileKey]: the selected one if it has that type, else the first bike of that type.
	 * The profile can be switched outside the bike button, so the selection alone is not enough.
	 */
	fun currentBike(selectedId: String?, profileKey: String): Bike? {
		val selected = getBike(selectedId)
		return if (selected?.profileKey == profileKey) selected else bikesOfProfile(profileKey).firstOrNull()
	}

	fun saveBike(bike: Bike): BikeGarage =
		copy(bikes = replaceOrAdd(bikes, bike) { it.id == bike.id })

	fun deleteBike(bike: Bike): BikeGarage = copy(bikes = bikes.filter { it.id != bike.id })

	fun saveBattery(battery: BikeBattery): BikeGarage =
		copy(batteries = replaceOrAdd(batteries, battery) { it.id == battery.id })

	/** Deletes the battery and takes it off every bike that used it. */
	fun deleteBattery(battery: BikeBattery): BikeGarage = copy(
		batteries = batteries.filter { it.id != battery.id },
		bikes = bikes.map { bike ->
			if (battery.id in bike.batteryIds) bike.copy(batteryIds = bike.batteryIds - battery.id) else bike
		}
	)

	fun newBikeId(): String = newId(BIKE_PREFIX, bikes.map { it.id })

	fun newBatteryId(): String = newId(BATTERY_PREFIX, batteries.map { it.id })

	/** "Speed pedelec", then "Speed pedelec 2", ... for the next bike of a type called [typeName]. */
	fun newBikeName(typeName: String): String {
		val names = bikes.map { it.name }.toSet()
		if (typeName !in names) {
			return typeName
		}
		var n = 2
		while ("$typeName $n" in names) {
			n++
		}
		return "$typeName $n"
	}

	/** The first of [palette] that no bike of [profileKey] uses yet, so bikes of one type look different. */
	fun freeColor(profileKey: String, palette: List<Int>): Int? {
		val used = bikesOfProfile(profileKey).mapNotNull { it.color }.toSet()
		return palette.firstOrNull { it !in used }
	}

	fun encode(): String = json.encodeToString(serializer(), this)

	companion object {
		private const val BIKE_PREFIX = "bike_"
		private const val BATTERY_PREFIX = "battery_"

		private val json = Json {
			ignoreUnknownKeys = true
			encodeDefaults = true
		}

		fun decode(value: String?): BikeGarage {
			if (value.isNullOrBlank()) {
				return BikeGarage()
			}
			return try {
				val garage = json.decodeFromString(serializer(), value)
				garage.copy(batteries = garage.batteries.filter { it.id.isNotEmpty() && it.capacityWh > 0 },
					bikes = garage.bikes.filter { it.id.isNotEmpty() && it.profileKey.isNotEmpty() })
			} catch (e: Exception) {
				BikeGarage()
			}
		}

		// deleting takes a battery off its bikes first, so reusing the id of a deleted one is safe
		private fun newId(prefix: String, ids: List<String>): String {
			val max = ids.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
			return prefix + (max + 1)
		}

		private fun <T> replaceOrAdd(list: List<T>, item: T, match: (T) -> Boolean): List<T> {
			val index = list.indexOfFirst(match)
			return if (index >= 0) list.toMutableList().also { it[index] = item } else list + item
		}
	}
}
