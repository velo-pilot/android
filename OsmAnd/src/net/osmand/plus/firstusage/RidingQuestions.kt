package net.osmand.plus.firstusage

import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import net.osmand.plus.BuildConfig
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.bikes.BikeDialogs
import net.osmand.plus.bikes.BikeHelper
import net.osmand.plus.render.RendererRegistry
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.UiUtilities
import net.osmand.plus.widgets.dialogbutton.DialogButton
import net.osmand.shared.vehicle.battery.Bike

/**
 * What the rider mostly rides for. Sets the routing parameters and the map style of all cycling profiles.
 */
enum class RidingUsage(
	@StringRes val titleId: Int,
	@StringRes val descriptionId: Int,
	@DrawableRes val iconId: Int,
	val drivingStyle: String,
	val nodeNetwork: String,
	val renderer: String
) {
	TRAVEL(R.string.riding_usage_travel, R.string.riding_usage_travel_descr, R.drawable.ic_action_work,
		"driving_style_balance", "node_network_off", RendererRegistry.VELOPILOT_TRAVEL_RENDER),
	LEISURE(R.string.riding_usage_leisure, R.string.riding_usage_leisure_descr, R.drawable.ic_action_map_sunset,
		"driving_style_leisure", "node_network_prefer", RendererRegistry.VELOPILOT_LEISURE_RENDER),
	SPORT(R.string.riding_usage_sport, R.string.riding_usage_sport_descr, R.drawable.ic_action_speed,
		"driving_style_speed", "node_network_off", RendererRegistry.VELOPILOT_TRAVEL_RENDER);

	/** Stored in [Bike.usage] */
	val key: String
		get() = name.lowercase()

	fun apply(app: OsmandApplication) {
		for (mode in RidingQuestions.ALL_BIKES) {
			applyTo(app, mode)
		}
	}

	fun applyTo(app: OsmandApplication, mode: ApplicationMode) {
		val settings = app.settings
		// Groups of boolean routing parameters (router_profiles/*.xml): exactly one of them is on. Parameters
		// a profile lacks are stored but not read.
		for (id in DRIVING_STYLES) {
			settings.getCustomRoutingBooleanProperty(id, false).setModeValue(mode, id == drivingStyle)
		}
		for (id in NODE_NETWORKS) {
			settings.getCustomRoutingBooleanProperty(id, false).setModeValue(mode, id == nodeNetwork)
		}
		settings.RENDERER.setModeValue(mode, renderer)
	}

	companion object {
		private val DRIVING_STYLES = listOf("driving_style_prefer_unpaved", "driving_style_safety",
			"driving_style_balance", "driving_style_speed", "driving_style_leisure")
		private val NODE_NETWORKS = listOf("node_network_off", "node_network_prefer", "node_network_strict")

		@JvmStatic
		fun fromKey(key: String?): RidingUsage = entries.firstOrNull { it.key == key } ?: TRAVEL
	}
}

/**
 * The first steps of the first usage wizard of cycling editions (BuildConfig.RIDING_QUESTIONS): which bike the
 * rider prefers as default profile (the three main bikes, more under "Other bike"), what they ride for,
 * their bikes (several of a type, each with its own ways of use) and the batteries of the electric ones.
 * [onDone] continues to the maps step.
 */
class RidingQuestions(
	private val app: OsmandApplication,
	private val view: View,
	private val nightMode: Boolean,
	private val onDone: Runnable
) {

	var step = STEP_BIKE
		private set

	private var bike = initialBike(app)
	// The other bikes are folded into one "Other bike" row until it is tapped, or one of them is the choice
	private var showOtherBikes = bike in ApplicationMode.OTHER_BIKES
	// In enum order, never empty: a rider can tick several, the first is in force at the start
	private var usages = listOf(RidingUsage.TRAVEL)
	private val usageKeys: List<String>
		get() = usages.map { it.key }

	private val bikeHelper = BikeHelper(app)

	private val title: TextView = view.findViewById(R.id.question_title)
	private val description: TextView = view.findViewById(R.id.question_description)
	private val options: LinearLayout = view.findViewById(R.id.question_options)
	private val backButton: DialogButton = view.findViewById(R.id.question_back_button)

	init {
		view.findViewById<DialogButton>(R.id.question_next_button).setOnClickListener { next() }
		backButton.setOnClickListener { show(previousStep(step)) }
	}

	fun show(step: Int) {
		this.step = step
		val appName = app.getString(R.string.app_name)
		options.removeAllViews()
		if (step == STEP_BIKE) {
			title.setText(R.string.riding_questions_bike_title)
			description.text = app.getString(R.string.riding_questions_bike_descr, appName)
			for (mode in if (showOtherBikes) ALL_BIKES else BIKES) {
				addOption(mode.iconRes, mode.toHumanString(), mode.description, mode == bike) {
					bike = mode
					show(STEP_BIKE)
				}
			}
			if (!showOtherBikes) {
				addOption(R.drawable.ic_action_arrow_down, app.getString(R.string.riding_questions_other_bike),
					app.getString(R.string.riding_questions_other_bike_descr), null) {
					showOtherBikes = true
					show(STEP_BIKE)
				}
			}
		} else if (step == STEP_BIKES) {
			title.setText(R.string.riding_questions_bikes_title)
			description.text = app.getString(R.string.riding_questions_bikes_descr, appName)
			val garage = bikeHelper.getGarage()
			for (item in garage.bikes) {
				val icon = bikeHelper.getIconRes(item)
				addOption(icon, item.name, bikeHelper.formatBikeSummary(item, garage), null) {
					BikeDialogs.showBikeEditor(view.context, item, nightMode, usageKeys) { show(STEP_BIKES) }
				}
			}
			addOption(R.drawable.ic_action_plus, app.getString(R.string.bike_add),
				app.getString(R.string.riding_questions_bikes_add_descr), null) {
				BikeDialogs.showBikeEditor(view.context, null, nightMode, usageKeys) { show(STEP_BIKES) }
			}
		} else if (step == STEP_BATTERIES) {
			title.setText(R.string.riding_questions_batteries_title)
			description.text = app.getString(R.string.riding_questions_batteries_descr, appName)
			val garage = bikeHelper.getGarage()
			for (item in electricBikes()) {
				val batteries = garage.batteriesOf(item)
				val summary = if (batteries.isEmpty()) app.getString(R.string.riding_questions_batteries_none)
					else batteries.joinToString(" + ") { battery -> bikeHelper.formatBattery(battery) }
				val icon = bikeHelper.getIconRes(item)
				addOption(icon, item.name, summary, null) {
					BikeDialogs.showBatteryPicker(view.context, item.name, item.batteryIds, nightMode) { ids ->
						val current = bikeHelper.getGarage()
						current.getBike(item.id)?.let { bikeHelper.saveGarage(current.saveBike(it.copy(batteryIds = ids))) }
						show(STEP_BATTERIES)
					}
				}
			}
		} else {
			title.setText(R.string.riding_questions_usage_title)
			description.setText(R.string.riding_questions_usage_descr)
			for (item in RidingUsage.entries) {
				val checked = item in usages
				addOption(item.iconId, app.getString(item.titleId), app.getString(item.descriptionId), checked,
					multiple = true) {
					if (!checked) {
						usages = (usages + item).sortedBy { it.ordinal }
					} else if (usages.size > 1) {
						usages = usages - item
					}
					show(STEP_USAGE)
				}
			}
		}
		backButton.visibility = if (step == STEP_BIKE) View.INVISIBLE else View.VISIBLE
		view.findViewById<TextView>(R.id.step_label).text =
			app.getString(R.string.riding_questions_step, steps().indexOf(step) + 1, totalSteps)
	}

	/** Steps shown, the maps step not included: batteries only when the rider has an electric bike. */
	private fun steps(): List<Int> =
		if (electricBikes().isEmpty()) listOf(STEP_BIKE, STEP_USAGE, STEP_BIKES)
		else listOf(STEP_BIKE, STEP_USAGE, STEP_BIKES, STEP_BATTERIES)

	/** All steps including the maps step that follows. */
	val totalSteps: Int
		get() = steps().size + 1

	private fun previousStep(step: Int): Int {
		val steps = steps()
		return steps.getOrNull(steps.indexOf(step) - 1) ?: STEP_BIKE
	}

	private fun nextStep(step: Int): Int? {
		val steps = steps()
		return steps.getOrNull(steps.indexOf(step) + 1)
	}

	/** The rider's bikes that always have a battery (speed pedelec, e-bike, e-scooter). */
	private fun electricBikes(): List<Bike> = bikeHelper.getGarage().bikes.filter { bike ->
		bikeHelper.getMode(bike)?.let { BikeHelper.hasMotor(it) } == true
	}

	private fun next() {
		when (step) {
			STEP_BIKE -> {
				applyBike()
				saveMainBike()
			}
			STEP_USAGE -> {
				// the bikes added in the next step get these ways of use too
				usages.first().apply(app)
				val garage = bikeHelper.getGarage()
				bikeHelper.saveGarage(garage.copy(bikes = garage.bikes.map {
					it.copy(usage = usages.first().key, usages = usageKeys)
				}))
			}
		}
		val next = nextStep(step)
		if (next != null) {
			show(next)
		} else {
			finish()
		}
	}

	private fun applyBike() {
		val settings = app.settings
		ApplicationMode.changeProfileAvailability(bike, true, app)
		settings.DEFAULT_APPLICATION_MODE.set(bike)
		settings.LAST_ROUTE_APPLICATION_MODE.set(bike)
		settings.setApplicationMode(bike)
	}

	/** The first bike is the chosen type; going back and choosing another type changes it. */
	private fun saveMainBike() {
		val settings = app.settings
		val garage = bikeHelper.getGarage()
		val main = garage.getBike(settings.SELECTED_BIKE.get())
		if (main == null) {
			val created = bikeHelper.createBike(bike.stringKey, usageKeys)
			bikeHelper.saveGarage(garage.saveBike(created))
			settings.SELECTED_BIKE.set(created.id)
		} else if (main.profileKey != bike.stringKey) {
			val oldTypeName = bikeHelper.getMode(main)?.toHumanString()
			val name = if (main.name == oldTypeName) garage.deleteBike(main).newBikeName(bike.toHumanString()) else main.name
			bikeHelper.saveGarage(garage.saveBike(main.copy(profileKey = bike.stringKey, name = name)))
		}
	}

	/** Keeps the profiles of the rider's bikes, hides the other bike profiles, and starts with the main bike. */
	private fun finish() {
		val garage = bikeHelper.getGarage()
		val types = garage.bikes.map { it.profileKey }.toSet()
		for (mode in ALL_BIKES) {
			ApplicationMode.changeProfileAvailability(mode, mode == bike || mode.stringKey in types, app)
		}
		(garage.getBike(app.settings.SELECTED_BIKE.get()) ?: garage.bikes.firstOrNull())?.let { bikeHelper.selectBike(it) }
		app.settings.RIDING_QUESTIONS_ANSWERED.set(true)
		onDone.run()
	}

	/** [selected] null: a row without a radio button; [multiple]: a check box instead of a radio button */
	private fun addOption(@DrawableRes iconId: Int, title: String, description: String, selected: Boolean?,
	                      multiple: Boolean = false, onClick: () -> Unit) {
		val item = UiUtilities.getInflater(view.context, nightMode)
			.inflate(R.layout.first_usage_option_item, options, false) as ViewGroup
		val color = if (selected == true) ColorUtilities.getActiveColor(app, nightMode)
			else ColorUtilities.getDefaultIconColor(app, nightMode)
		item.findViewById<ImageView>(R.id.icon).setImageDrawable(app.uiUtilities.getPaintedIcon(iconId, color))
		item.findViewById<TextView>(R.id.title).text = title
		item.findViewById<TextView>(R.id.description).text = description
		val radio = item.findViewById<CompoundButton>(R.id.compound_button)
		val checkBox = item.findViewById<CompoundButton>(R.id.check_box)
		val button = if (multiple) checkBox else radio
		UiUtilities.setupCompoundButton(nightMode, ColorUtilities.getActiveColor(app, nightMode), button)
		button.isChecked = selected == true
		button.visibility = if (selected == null) View.INVISIBLE else View.VISIBLE
		(if (multiple) radio else checkBox).visibility = View.GONE
		item.setOnClickListener { onClick() }
		options.addView(item)
	}

	companion object {
		const val STEP_BIKE = 0
		const val STEP_USAGE = 1
		const val STEP_BIKES = 2
		const val STEP_BATTERIES = 3

		@JvmField
		val BIKES = listOf(ApplicationMode.BICYCLE, ApplicationMode.E_BIKE, ApplicationMode.SPEED_PEDELEC)

		/** [BIKES] and the bikes under "Other bike" */
		@JvmField
		val ALL_BIKES = BIKES + ApplicationMode.OTHER_BIKES

		@JvmStatic
		fun isNeeded(app: OsmandApplication): Boolean {
			return BuildConfig.RIDING_QUESTIONS && !app.settings.RIDING_QUESTIONS_ANSWERED.get()
		}

		private fun initialBike(app: OsmandApplication): ApplicationMode {
			val mode = app.settings.DEFAULT_APPLICATION_MODE.get()
			return if (mode in ALL_BIKES) mode else ApplicationMode.SPEED_PEDELEC
		}
	}
}
