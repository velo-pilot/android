package net.osmand.plus.bikes

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.firstusage.RidingUsage
import net.osmand.plus.routing.RouteService
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.shared.vehicle.battery.BatteryEstimate
import net.osmand.shared.vehicle.battery.BatteryProfile
import net.osmand.shared.vehicle.battery.BatteryRangeEstimator
import net.osmand.shared.vehicle.battery.Bike
import net.osmand.shared.vehicle.battery.BikeBattery
import net.osmand.shared.vehicle.battery.BikeGarage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The user's bikes and batteries. A bike has a type (the app profile it is ridden with), a riding mode and
 * batteries; several bikes can share a type and a battery can serve several bikes. Selecting a bike switches
 * to its profile and applies its riding mode.
 */
class BikeHelper(private val app: OsmandApplication) {

	private val settings = app.settings

	fun getGarage(): BikeGarage = BikeGarage.decode(settings.BIKE_GARAGE.get())

	fun saveGarage(garage: BikeGarage) {
		settings.BIKE_GARAGE.set(garage.encode())
	}

	fun hasBikes(): Boolean = getGarage().bikes.isNotEmpty()

	fun getMode(bike: Bike): ApplicationMode? = ApplicationMode.valueOfStringKey(bike.profileKey, null)

	/** The bike ridden with [mode]: the selected bike if it has that type, else the first of that type. */
	fun getCurrentBike(mode: ApplicationMode = settings.applicationMode): Bike? =
		getGarage().currentBike(settings.SELECTED_BIKE.get(), mode.stringKey)

	/** Switches to the bike's profile and applies its current way of use ([Bike.usage]) to it. */
	fun selectBike(bike: Bike) {
		val mode = getMode(bike) ?: return
		val garage = getGarage()
		garage.getBike(bike.id)?.takeIf { it.usage != bike.usage }?.let {
			saveGarage(garage.saveBike(it.copy(usage = bike.usage)))
		}
		if (mode !in ApplicationMode.values(app)) {
			ApplicationMode.changeProfileAvailability(mode, true, app)
		}
		settings.SELECTED_BIKE.set(bike.id)
		RidingUsage.fromKey(bike.usage).applyTo(app, mode)
		settings.setApplicationMode(mode)
		app.routingHelper.onSettingsChanged(mode, true)
	}

	fun createBike(profileKey: String, usages: List<String>): Bike {
		val garage = getGarage()
		val typeName = ApplicationMode.valueOfStringKey(profileKey, null)?.toHumanString() ?: profileKey
		// the second bike of a type gets a colour of its own, so the two can be told apart at a glance
		val color = if (garage.bikesOfProfile(profileKey).isEmpty()) null else garage.freeColor(profileKey, BIKE_COLORS)
		return Bike(garage.newBikeId(), garage.newBikeName(typeName), profileKey, usages.first(), color = color, usages = usages)
	}

	@DrawableRes
	fun getIconRes(bike: Bike?): Int {
		val iconName = bike?.iconName
		if (iconName != null) {
			val id = app.resources.getIdentifier(iconName, "drawable", app.packageName)
			if (id != 0) {
				return id
			}
		}
		return bike?.let { getMode(it)?.iconRes } ?: R.drawable.ic_action_bicycle_dark
	}

	fun getIconName(bike: Bike?): String = app.resources.getResourceEntryName(getIconRes(bike))

	@ColorInt
	fun getColor(bike: Bike?, nightMode: Boolean): Int {
		bike?.color?.let { return it }
		val mode = bike?.let { getMode(it) } ?: settings.applicationMode
		return mode.getProfileColor(nightMode)
	}

	fun createBattery(name: String, capacityWh: Double): BikeBattery =
		BikeBattery(getGarage().newBatteryId(), name, capacityWh)

	/** Bike types whose bikes can have a battery: all but the plain bicycle. */
	fun canHaveBattery(profileKey: String): Boolean {
		val mode = ApplicationMode.valueOfStringKey(profileKey, null)
		return mode != null && isBatteryMode(mode)
	}

	fun getProfile(mode: ApplicationMode, bike: Bike?): BatteryProfile {
		val profile = BatteryProfile.forProfile(mode.routingProfile)
		val custom = bike?.consumptionWhPerKm
		return if (custom != null && custom > 0) profile.withConsumption(custom) else profile
	}

	fun getDefaultConsumption(profileKey: String): Double {
		val mode = ApplicationMode.valueOfStringKey(profileKey, null)
		return BatteryProfile.forProfile(mode?.routingProfile ?: profileKey).consumptionWhPerKm
	}

	fun getEstimate(mode: ApplicationMode, distanceMeters: Double, climbMeters: Double): BikeEstimate? {
		if (!isBatteryMode(mode) || distanceMeters <= 0) {
			return null
		}
		val garage = getGarage()
		val bike = garage.currentBike(settings.SELECTED_BIKE.get(), mode.stringKey)
		val capacities = bike?.let { garage.batteriesOf(it).map { battery -> battery.capacityWh } }.orEmpty()
		if (capacities.isEmpty() && !hasMotor(mode)) {
			return null
		}
		val estimate = BatteryRangeEstimator.estimate(distanceMeters, climbMeters, getProfile(mode, bike), capacities)
		return BikeEstimate(bike, estimate)
	}

	fun formatEstimate(bikeEstimate: BikeEstimate): String {
		val estimate = bikeEstimate.estimate
		val needed = formatWh(estimate.neededWh)
		val text = when {
			!estimate.hasBatteries -> app.getString(R.string.bike_battery_estimate_needed, needed)
			!estimate.isEnough -> app.getString(
				R.string.bike_battery_estimate_not_enough, needed, formatWh(estimate.totalCapacityWh),
				OsmAndFormatter.getFormattedDistance(estimate.rangeMeters.toFloat(), app))
			estimate.singleBatteryCharge != null -> app.getString(
				R.string.bike_battery_estimate_one_battery, formatPercent(estimate.singleBatteryCharge!!), needed)
			estimate.capacitiesWh.size > 1 -> app.getString(
				R.string.bike_battery_estimate_all_batteries, formatPercent(estimate.requiredCharge), needed)
			else -> app.getString(R.string.bike_battery_estimate_charge, formatPercent(estimate.requiredCharge), needed)
		}
		val bike = bikeEstimate.bike
		return if (bike != null && getGarage().bikes.size > 1) {
			app.getString(R.string.ltr_or_rtl_combine_via_colon, bike.name, text)
		} else {
			text
		}
	}

	fun formatUsages(usages: List<String>): String =
		usages.joinToString(", ") { app.getString(RidingUsage.fromKey(it).titleId) }

	/** "Speed pedelec · Travel, Leisure · PowerTube 625 + Spare" */
	fun formatBikeSummary(bike: Bike, garage: BikeGarage = getGarage()): String {
		val parts = mutableListOf<String>()
		ApplicationMode.valueOfStringKey(bike.profileKey, null)?.let { parts.add(it.toHumanString()) }
		parts.add(formatUsages(bike.allUsages))
		val batteries = garage.batteriesOf(bike)
		if (batteries.isNotEmpty()) {
			parts.add(formatBatteries(batteries))
		}
		return parts.joinToString(" · ")
	}

	fun formatBatteries(batteries: List<BikeBattery>): String =
		batteries.joinToString(" + ") { it.name }

	fun formatBattery(battery: BikeBattery): String =
		app.getString(R.string.ltr_or_rtl_combine_via_dash, battery.name, formatWh(battery.capacityWh))

	fun formatWh(wh: Double): String {
		// estimates are rounded to 5 Wh, entered capacities are shown as they are
		val value = if (wh == wh.roundToLong().toDouble()) wh.roundToLong() else (wh / 5).roundToLong() * 5
		return app.getString(R.string.ltr_or_rtl_combine_via_space, value.toString(), app.getString(R.string.unit_wh))
	}

	fun formatConsumption(whPerKm: Double): String =
		app.getString(R.string.ltr_or_rtl_combine_via_space, formatNumber(whPerKm), app.getString(R.string.unit_wh_per_km))

	/** The estimate for the bike it was made for (null: no bike of that type, profile defaults). */
	data class BikeEstimate(val bike: Bike?, val estimate: BatteryEstimate)

	companion object {

		/** Symbols a bike can have: bike types first, then signs to tell bikes of one type apart */
		@JvmField
		val BIKE_ICONS = listOf(R.drawable.ic_action_bicycle_dark, R.drawable.ic_action_electric_bike,
			R.drawable.ic_action_speed_pedelec, R.drawable.ic_action_cargo_bike, R.drawable.ic_action_road_bike,
			R.drawable.ic_action_mountain_bike, R.drawable.ic_action_velomobile, R.drawable.ic_action_trike,
			R.drawable.ic_action_scooter, R.drawable.ic_action_monowheel, R.drawable.ic_action_personal_transporter,
			R.drawable.ic_action_motor_scooter, R.drawable.ic_action_work, R.drawable.ic_action_home_dark,
			R.drawable.ic_action_map_sunset, R.drawable.ic_action_speed, R.drawable.ic_action_favorite,
			R.drawable.ic_action_flag, R.drawable.ic_action_sun, R.drawable.ic_action_moon)

		/** Bike colours: strong enough on the light and the dark map */
		@JvmField
		val BIKE_COLORS = listOf(0xFF1582DE, 0xFF002467, 0xFF00897B, 0xFF43A047, 0xFFF9A825, 0xFFFB8C00,
			0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF6D4C41, 0xFF546E7A, 0xFF212121).map { it.toInt() }

		private val MOTOR_PROFILES = setOf("speed_pedelec", "e_bike", "e_scooter")
		private val NO_BATTERY_PROFILES = setOf("bicycle", "moped")

		/** Bike profiles where a battery can matter: all but the plain bicycle and the moped. */
		@JvmStatic
		fun isBatteryMode(mode: ApplicationMode): Boolean =
			mode.isDerivedRoutingFrom(ApplicationMode.BICYCLE)
					&& mode.routeService == RouteService.OSMAND
					&& mode.routingProfile !in NO_BATTERY_PROFILES

		/** Bikes of these types always have a motor, so they show the energy a route needs even without batteries. */
		@JvmStatic
		fun hasMotor(mode: ApplicationMode): Boolean = mode.routingProfile in MOTOR_PROFILES

		/** "At least" percentage: rounded up to 5 %. */
		@JvmStatic
		fun formatPercent(fraction: Double): String {
			val percent = max(5L, (ceil(fraction * 20 - 1e-9) * 5).toLong())
			return "$percent%"
		}

		@JvmStatic
		fun formatNumber(value: Double): String {
			val rounded = value.roundToLong()
			return if (rounded.toDouble() == value) rounded.toString() else String.format("%.1f", value)
		}
	}
}
