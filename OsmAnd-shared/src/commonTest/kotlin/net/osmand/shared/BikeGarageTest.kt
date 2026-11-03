package net.osmand.shared

import net.osmand.shared.vehicle.battery.Bike
import net.osmand.shared.vehicle.battery.BikeBattery
import net.osmand.shared.vehicle.battery.BikeGarage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BikeGarageTest {

	private val powerTube = BikeBattery("battery_1", "Bosch PowerTube 625", 625.0)
	private val spare = BikeBattery("battery_2", "Spare 500", 500.0)
	private val stromer = Bike("bike_1", "Stromer", "speed_pedelec", Bike.USAGE_TRAVEL, listOf("battery_1", "battery_2"))
	private val riese = Bike("bike_2", "Riese & Müller", "speed_pedelec", Bike.USAGE_LEISURE, listOf("battery_1"))
	private val gazelle = Bike("bike_3", "Gazelle", "e_bike", Bike.USAGE_LEISURE, listOf("battery_1"), 7.5)
	private val garage = BikeGarage(listOf(stromer, riese, gazelle), listOf(powerTube, spare))

	@Test
	fun encodeAndDecode() {
		assertEquals(garage, BikeGarage.decode(garage.encode()))
	}

	@Test
	fun brokenOrEmptyValuesGiveAnEmptyGarage() {
		assertEquals(BikeGarage(), BikeGarage.decode(null))
		assertEquals(BikeGarage(), BikeGarage.decode(""))
		assertEquals(BikeGarage(), BikeGarage.decode("not json"))
		val zeroCapacity = """{"batteries":[{"id":"battery_1","name":"x","capacityWh":0}]}"""
		assertEquals(BikeGarage(), BikeGarage.decode(zeroCapacity))
	}

	@Test
	fun oneBatteryServesSeveralBikes() {
		assertEquals(listOf(powerTube, spare), garage.batteriesOf(stromer))
		assertEquals(listOf(powerTube), garage.batteriesOf(gazelle))
		assertEquals(listOf(stromer, riese, gazelle), garage.bikesUsing(powerTube))
		assertEquals(listOf(stromer), garage.bikesUsing(spare))
	}

	@Test
	fun editingABatteryChangesItForEveryBike() {
		val worn = powerTube.copy(capacityWh = 560.0)
		val edited = garage.saveBattery(worn)
		assertEquals(listOf(worn, spare), edited.batteriesOf(stromer))
		assertEquals(listOf(worn), edited.batteriesOf(gazelle))
	}

	@Test
	fun deletingABatteryTakesItOffItsBikes() {
		val edited = garage.deleteBattery(powerTube)
		assertEquals(listOf(spare), edited.batteriesOf(stromer))
		assertEquals(emptyList(), edited.getBike("bike_3")!!.batteryIds)
		// a new battery may get a free id but never shows up on a bike by itself
		val added = edited.saveBattery(BikeBattery(edited.newBatteryId(), "New", 750.0))
		assertEquals(listOf(spare), added.batteriesOf(added.getBike("bike_1")!!))
	}

	@Test
	fun currentBikeFollowsTheProfile() {
		// selected bike of the profile
		assertEquals(riese, garage.currentBike("bike_2", "speed_pedelec"))
		// profile switched elsewhere: first bike of that type
		assertEquals(gazelle, garage.currentBike("bike_2", "e_bike"))
		assertEquals(stromer, garage.currentBike(null, "speed_pedelec"))
		assertNull(garage.currentBike("bike_1", "bicycle"))
	}

	@Test
	fun saveAndDeleteBikes() {
		val renamed = stromer.copy(name = "Stromer ST3")
		assertEquals(listOf(renamed, riese, gazelle), garage.saveBike(renamed).bikes)
		val added = Bike(garage.newBikeId(), "Cargo", "cargo_bike")
		assertEquals("bike_4", added.id)
		assertEquals(listOf(stromer, riese, gazelle, added), garage.saveBike(added).bikes)
		assertEquals(listOf(stromer, gazelle), garage.deleteBike(riese).bikes)
	}

	@Test
	fun newNamesAndIds() {
		assertEquals("battery_3", garage.newBatteryId())
		assertEquals("E-bike", garage.newBikeName("E-bike"))
		val twoEbikes = garage.saveBike(Bike("bike_9", "E-bike", "e_bike"))
		assertEquals("E-bike 2", twoEbikes.newBikeName("E-bike"))
		assertEquals("bike_10", twoEbikes.newBikeId())
	}

	@Test
	fun symbolAndColourSurviveSaving() {
		val styled = stromer.copy(iconName = "ic_action_speed_pedelec", color = 0xFF1582DE.toInt())
		val saved = BikeGarage.decode(garage.saveBike(styled).encode())
		assertEquals(styled, saved.getBike("bike_1"))
		// garages saved before bikes had a symbol still load
		val old = """{"bikes":[{"id":"bike_1","name":"Stromer","profileKey":"speed_pedelec"}]}"""
		assertEquals(Bike("bike_1", "Stromer", "speed_pedelec"), BikeGarage.decode(old).getBike("bike_1"))
	}

	@Test
	fun newBikesOfATypeGetAnUnusedColour() {
		val palette = listOf(1, 2, 3)
		val withColour = garage.saveBike(stromer.copy(color = 1))
		assertEquals(2, withColour.freeColor("speed_pedelec", palette))
		assertEquals(1, withColour.freeColor("e_bike", palette))
	}

	@Test
	fun oneBikeCanBeUsedInSeveralWays() {
		val both = stromer.withUsages(listOf(Bike.USAGE_TRAVEL, Bike.USAGE_LEISURE))
		assertEquals(listOf(Bike.USAGE_TRAVEL, Bike.USAGE_LEISURE), both.allUsages)
		assertEquals(Bike.USAGE_TRAVEL, both.usage)
		assertEquals(both, BikeGarage.decode(garage.saveBike(both).encode()).getBike("bike_1"))
	}

	@Test
	fun bikesSavedWithOneUsageKeepIt() {
		val old = """{"bikes":[{"id":"bike_1","name":"Stromer","profileKey":"speed_pedelec","usage":"leisure"}]}"""
		assertEquals(listOf(Bike.USAGE_LEISURE), BikeGarage.decode(old).getBike("bike_1")!!.allUsages)
	}

	@Test
	fun removingTheCurrentUsageMovesToARemainingOne() {
		val both = stromer.withUsages(listOf(Bike.USAGE_TRAVEL, Bike.USAGE_LEISURE))
		val leisure = both.withUsages(listOf(Bike.USAGE_LEISURE, Bike.USAGE_SPORT))
		assertEquals(Bike.USAGE_LEISURE, leisure.usage)
		// a bike always keeps at least one way of use
		assertEquals(both, both.withUsages(emptyList()))
	}
}
