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
import net.osmand.plus.render.RendererRegistry
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.UiUtilities
import net.osmand.plus.widgets.dialogbutton.DialogButton

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

	fun apply(app: OsmandApplication) {
		val settings = app.settings
		for (mode in RidingQuestions.BIKES) {
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
	}

	companion object {
		private val DRIVING_STYLES = listOf("driving_style_prefer_unpaved", "driving_style_safety",
			"driving_style_balance", "driving_style_speed", "driving_style_leisure")
		private val NODE_NETWORKS = listOf("node_network_off", "node_network_prefer", "node_network_strict")
	}
}

/**
 * The first steps of the first usage wizard of cycling editions (BuildConfig.RIDING_QUESTIONS): which bike the
 * rider prefers as default profile, and what they mostly ride for. [onDone] continues to the maps step.
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
	private var usage = RidingUsage.TRAVEL

	private val title: TextView = view.findViewById(R.id.question_title)
	private val description: TextView = view.findViewById(R.id.question_description)
	private val options: LinearLayout = view.findViewById(R.id.question_options)
	private val backButton: DialogButton = view.findViewById(R.id.question_back_button)

	init {
		view.findViewById<DialogButton>(R.id.question_next_button).setOnClickListener { next() }
		backButton.setOnClickListener { show(STEP_BIKE) }
	}

	fun show(step: Int) {
		this.step = step
		val appName = app.getString(R.string.app_name)
		options.removeAllViews()
		if (step == STEP_BIKE) {
			title.setText(R.string.riding_questions_bike_title)
			description.text = app.getString(R.string.riding_questions_bike_descr, appName)
			for (mode in BIKES) {
				addOption(mode.iconRes, mode.toHumanString(), mode.description, mode == bike) {
					bike = mode
					show(STEP_BIKE)
				}
			}
		} else {
			title.setText(R.string.riding_questions_usage_title)
			description.setText(R.string.riding_questions_usage_descr)
			for (item in RidingUsage.entries) {
				addOption(item.iconId, app.getString(item.titleId), app.getString(item.descriptionId), item == usage) {
					usage = item
					show(STEP_USAGE)
				}
			}
		}
		backButton.visibility = if (step == STEP_BIKE) View.INVISIBLE else View.VISIBLE
		view.findViewById<TextView>(R.id.step_label).text =
			app.getString(R.string.riding_questions_step, step + 1, STEPS_WITH_MAPS)
	}

	private fun next() {
		if (step == STEP_BIKE) {
			applyBike()
			show(STEP_USAGE)
		} else {
			usage.apply(app)
			app.settings.RIDING_QUESTIONS_ANSWERED.set(true)
			onDone.run()
		}
	}

	private fun applyBike() {
		val settings = app.settings
		ApplicationMode.changeProfileAvailability(bike, true, app)
		settings.DEFAULT_APPLICATION_MODE.set(bike)
		settings.LAST_ROUTE_APPLICATION_MODE.set(bike)
		settings.setApplicationMode(bike)
	}

	private fun addOption(@DrawableRes iconId: Int, title: String, description: String, selected: Boolean,
	                      onClick: () -> Unit) {
		val item = UiUtilities.getInflater(view.context, nightMode)
			.inflate(R.layout.first_usage_option_item, options, false) as ViewGroup
		val color = if (selected) ColorUtilities.getActiveColor(app, nightMode)
			else ColorUtilities.getDefaultIconColor(app, nightMode)
		item.findViewById<ImageView>(R.id.icon).setImageDrawable(app.uiUtilities.getPaintedIcon(iconId, color))
		item.findViewById<TextView>(R.id.title).text = title
		item.findViewById<TextView>(R.id.description).text = description
		val radio = item.findViewById<CompoundButton>(R.id.compound_button)
		UiUtilities.setupCompoundButton(nightMode, ColorUtilities.getActiveColor(app, nightMode), radio)
		radio.isChecked = selected
		item.setOnClickListener { onClick() }
		options.addView(item)
	}

	companion object {
		const val STEP_BIKE = 0
		const val STEP_USAGE = 1
		private const val STEPS_WITH_MAPS = 3

		@JvmField
		val BIKES = listOf(ApplicationMode.BICYCLE, ApplicationMode.E_BIKE, ApplicationMode.SPEED_PEDELEC)

		@JvmStatic
		fun isNeeded(app: OsmandApplication): Boolean {
			return BuildConfig.RIDING_QUESTIONS && !app.settings.RIDING_QUESTIONS_ANSWERED.get()
		}

		private fun initialBike(app: OsmandApplication): ApplicationMode {
			val mode = app.settings.DEFAULT_APPLICATION_MODE.get()
			return if (mode in BIKES) mode else ApplicationMode.SPEED_PEDELEC
		}
	}
}
