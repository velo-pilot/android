package net.osmand.shared.vehicle.battery

import kotlin.math.max

/**
 * Energy use of an electric bike or scooter: a flat-ground consumption per kilometre plus the energy the
 * motor puts into climbing. Descents give nothing back (bike motors do not recuperate).
 */
data class BatteryProfile(
	val consumptionWhPerKm: Double,
	val climbWhPerMeter: Double
) {
	fun withConsumption(consumptionWhPerKm: Double): BatteryProfile {
		// the motor's share of climbing grows with the assistance, so climbing scales with consumption
		val climb = if (this.consumptionWhPerKm > 0) {
			climbWhPerMeter * consumptionWhPerKm / this.consumptionWhPerKm
		} else {
			climbWhPerMeter
		}
		return BatteryProfile(consumptionWhPerKm, climb)
	}

	companion object {
		private const val GRAVITY = 9.81
		private const val DRIVE_EFFICIENCY = 0.8

		/** Wh the battery spends per metre climbed: rider and bike weigh [massKg], the motor delivers [motorShare]. */
		fun climbWhPerMeter(massKg: Double, motorShare: Double): Double =
			massKg * GRAVITY / 3600 * motorShare / DRIVE_EFFICIENCY

		// ~16 Wh/km riding 35-45 km/h; the motor does most of the work
		val SPEED_PEDELEC = BatteryProfile(16.0, climbWhPerMeter(115.0, 0.7))

		// ~8 Wh/km at 25 km/h in a medium assist level
		val E_BIKE = BatteryProfile(8.0, climbWhPerMeter(110.0, 0.5))

		// kick scooter: no pedalling, the motor does everything
		val E_SCOOTER = BatteryProfile(11.0, climbWhPerMeter(95.0, 1.0))

		// cargo bikes, trikes, velomobiles... with an assisting motor
		val OTHER = BatteryProfile(10.0, climbWhPerMeter(130.0, 0.5))

		fun forProfile(profileKey: String?): BatteryProfile = when (profileKey) {
			"speed_pedelec" -> SPEED_PEDELEC
			"e_bike" -> E_BIKE
			"e_scooter" -> E_SCOOTER
			else -> OTHER
		}
	}
}

/**
 * Whether the batteries last a route of [distanceMeters] that needs [neededWh] (safety margin included).
 */
data class BatteryEstimate(
	val distanceMeters: Double,
	val neededWh: Double,
	val capacitiesWh: List<Double>
) {
	val totalCapacityWh: Double = capacitiesWh.sum()

	val hasBatteries: Boolean = totalCapacityWh > 0

	val isEnough: Boolean = hasBatteries && neededWh <= totalCapacityWh

	/** Charge every battery needs when all are charged to the same level; above 1 when they cannot make it. */
	val requiredCharge: Double = if (hasBatteries) neededWh / totalCapacityWh else 0.0

	/** Charge the largest battery needs to do the route on its own, when there are several and one is enough. */
	val singleBatteryCharge: Double? = if (capacitiesWh.size > 1) {
		val charge = neededWh / capacitiesWh.max()
		if (charge <= 1.0) charge else null
	} else {
		null
	}

	/** Distance full batteries cover on this route (same mix of climbing), keeping the safety margin. */
	val rangeMeters: Double = if (neededWh > 0) distanceMeters * totalCapacityWh / neededWh else 0.0

	val missingWh: Double = max(0.0, neededWh - totalCapacityWh)
}

object BatteryRangeEstimator {

	/** Extra energy kept for headwind, cold, detours and battery ageing. */
	const val SAFETY_MARGIN = 0.15

	fun neededWh(distanceMeters: Double, climbMeters: Double, profile: BatteryProfile): Double {
		val flat = distanceMeters / 1000 * profile.consumptionWhPerKm
		val climb = max(0.0, climbMeters) * profile.climbWhPerMeter
		return (flat + climb) * (1 + SAFETY_MARGIN)
	}

	fun estimate(
		distanceMeters: Double,
		climbMeters: Double,
		profile: BatteryProfile,
		capacitiesWh: List<Double>
	): BatteryEstimate {
		val distance = max(0.0, distanceMeters)
		return BatteryEstimate(distance, neededWh(distance, climbMeters, profile), capacitiesWh)
	}
}
