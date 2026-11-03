package net.osmand.shared

import net.osmand.shared.vehicle.battery.BatteryProfile
import net.osmand.shared.vehicle.battery.BatteryRangeEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryRangeEstimatorTest {

	private val flat = BatteryProfile(16.0, 0.25)

	@Test
	fun flatRouteNeedsConsumptionTimesDistancePlusMargin() {
		val needed = BatteryRangeEstimator.neededWh(20_000.0, 0.0, flat)
		assertEquals(16.0 * 20 * 1.15, needed, 1e-9)
	}

	@Test
	fun climbingCostsExtraAndNegativeClimbIsIgnored() {
		val withClimb = BatteryRangeEstimator.neededWh(10_000.0, 200.0, flat)
		assertEquals((160.0 + 50.0) * 1.15, withClimb, 1e-9)
		assertEquals(160.0 * 1.15, BatteryRangeEstimator.neededWh(10_000.0, -50.0, flat), 1e-9)
	}

	@Test
	fun singleBatteryThatIsLargeEnough() {
		// 20 km flat = 368 Wh of a 625 Wh battery
		val estimate = BatteryRangeEstimator.estimate(20_000.0, 0.0, flat, listOf(625.0))
		assertTrue(estimate.isEnough)
		assertEquals(368.0 / 625.0, estimate.requiredCharge, 1e-9)
		assertNull(estimate.singleBatteryCharge)
		assertEquals(0.0, estimate.missingWh, 1e-9)
		assertEquals(20_000.0 * 625.0 / 368.0, estimate.rangeMeters, 1e-6)
	}

	@Test
	fun batteryTooSmall() {
		val estimate = BatteryRangeEstimator.estimate(50_000.0, 0.0, flat, listOf(625.0))
		assertFalse(estimate.isEnough)
		assertTrue(estimate.requiredCharge > 1.0)
		assertEquals(920.0 - 625.0, estimate.missingWh, 1e-9)
		assertTrue(estimate.rangeMeters < 50_000.0)
	}

	@Test
	fun twoBatteriesWhereOneIsEnough() {
		val estimate = BatteryRangeEstimator.estimate(20_000.0, 0.0, flat, listOf(500.0, 625.0))
		assertTrue(estimate.isEnough)
		assertEquals(368.0 / 1125.0, estimate.requiredCharge, 1e-9)
		val single = assertNotNull(estimate.singleBatteryCharge)
		assertEquals(368.0 / 625.0, single, 1e-9)
	}

	@Test
	fun twoBatteriesThatAreNeededTogether() {
		val estimate = BatteryRangeEstimator.estimate(50_000.0, 0.0, flat, listOf(500.0, 625.0))
		assertTrue(estimate.isEnough)
		assertNull(estimate.singleBatteryCharge)
		assertEquals(920.0 / 1125.0, estimate.requiredCharge, 1e-9)
	}

	@Test
	fun noBatteriesStillGivesTheEnergyNeeded() {
		val estimate = BatteryRangeEstimator.estimate(20_000.0, 0.0, flat, emptyList())
		assertFalse(estimate.hasBatteries)
		assertFalse(estimate.isEnough)
		assertEquals(368.0, estimate.neededWh, 1e-9)
	}

	@Test
	fun customConsumptionScalesClimbing() {
		val profile = BatteryProfile.SPEED_PEDELEC.withConsumption(24.0)
		assertEquals(24.0, profile.consumptionWhPerKm, 1e-9)
		assertEquals(BatteryProfile.SPEED_PEDELEC.climbWhPerMeter * 1.5, profile.climbWhPerMeter, 1e-9)
	}

	@Test
	fun profilesPerAppMode() {
		assertEquals(BatteryProfile.SPEED_PEDELEC, BatteryProfile.forProfile("speed_pedelec"))
		assertEquals(BatteryProfile.E_BIKE, BatteryProfile.forProfile("e_bike"))
		assertEquals(BatteryProfile.E_SCOOTER, BatteryProfile.forProfile("e_scooter"))
		assertEquals(BatteryProfile.OTHER, BatteryProfile.forProfile("cargo_bike"))
		// a 100 m climb on a speed pedelec costs ~27 Wh, about 1.7 km of flat riding
		assertEquals(27.0, BatteryProfile.SPEED_PEDELEC.climbWhPerMeter * 100, 1.0)
	}
}
