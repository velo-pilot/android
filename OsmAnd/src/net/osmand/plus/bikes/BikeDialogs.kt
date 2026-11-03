package net.osmand.plus.bikes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.firstusage.RidingQuestions
import net.osmand.plus.firstusage.RidingUsage
import net.osmand.plus.helpers.AndroidUiHelper
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.UiUtilities
import net.osmand.shared.vehicle.battery.Bike
import net.osmand.shared.vehicle.battery.BikeBattery

/**
 * Dialogs for the user's bikes and batteries: pick the bike to ride, list and edit bikes, and choose and edit
 * the batteries of a bike. Batteries are shared, so editing one changes it for every bike that uses it.
 */
object BikeDialogs {

	/** Picks the bike to ride; [onSelected] switches to it. Opens the bike list when there are no bikes yet. */
	@JvmStatic
	fun showBikePicker(context: Context, nightMode: Boolean, onSelected: (Bike) -> Unit) {
		val app = context.applicationContext as OsmandApplication
		val helper = BikeHelper(app)
		val garage = helper.getGarage()
		if (garage.bikes.isEmpty()) {
			showGarage(context, nightMode, null)
			return
		}
		val ctx = UiUtilities.getThemedContext(context, nightMode)
		val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bike_battery, null, false)
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.description), false)
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.add_battery), false)
		val container = view.findViewById<LinearLayout>(R.id.values)
		var current = helper.getCurrentBike()
		lateinit var dialog: AlertDialog
		lateinit var fill: () -> Unit
		fill = {
			container.removeAllViews()
			for (bike in garage.bikes) {
				val ridden = bike.id == current?.id
				addRow(ctx, app, container, nightMode, helper.getIconRes(bike), bike.name,
					helper.formatBikeSummary(bike, garage), ridden, iconColor = helper.getColor(bike, nightMode)) {
					if (!ridden) {
						current = bike
						onSelected(bike)
					}
					// a bike with one use is done; with several, its uses show up below to choose from
					if (bike.allUsages.size > 1) {
						fill()
					} else {
						dialog.dismiss()
					}
				}
			}
			val bike = current
			if (bike != null && bike.allUsages.size > 1) {
				addHeader(ctx, app, container, nightMode, R.string.bike_riding)
				for (key in bike.allUsages) {
					val usage = RidingUsage.fromKey(key)
					addRow(ctx, app, container, nightMode, usage.iconId, app.getString(usage.titleId),
						app.getString(usage.descriptionId), key == bike.usage) {
						dialog.dismiss()
						if (key != bike.usage) {
							onSelected(bike.copy(usage = key))
						}
					}
				}
			}
		}
		fill()
		dialog = AlertDialog.Builder(ctx)
			.setTitle(R.string.bike_picker_title)
			.setView(view)
			.setNegativeButton(R.string.shared_string_close, null)
			.setNeutralButton(R.string.bike_manage) { _, _ -> showGarage(context, nightMode, null) }
			.show()
	}

	/** The user's bikes: tap one to edit it, or add one. */
	@JvmStatic
	fun showGarage(context: Context, nightMode: Boolean, onChanged: Runnable?) {
		val app = context.applicationContext as OsmandApplication
		val helper = BikeHelper(app)
		val ctx = UiUtilities.getThemedContext(context, nightMode)
		val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bike_battery, null, false)
		view.findViewById<TextView>(R.id.description).setText(R.string.bike_garage_descr)
		val container = view.findViewById<LinearLayout>(R.id.values)
		val addButton = view.findViewById<MaterialButton>(R.id.add_battery)
		addButton.setText(R.string.bike_add)

		lateinit var fill: () -> Unit
		val changed = {
			fill()
			onChanged?.run()
		}
		fill = {
			container.removeAllViews()
			val garage = helper.getGarage()
			for (bike in garage.bikes) {
				addRow(ctx, app, container, nightMode, helper.getIconRes(bike), bike.name,
					helper.formatBikeSummary(bike, garage), null, iconColor = helper.getColor(bike, nightMode)) {
					showBikeEditor(context, bike, nightMode) { changed() }
				}
			}
			if (garage.bikes.isEmpty()) {
				addRow(ctx, app, container, nightMode, R.drawable.ic_action_bicycle_dark,
					app.getString(R.string.bike_none), app.getString(R.string.bike_none_descr), null, onClick = null)
			}
		}
		fill()
		addButton.setOnClickListener { showBikeEditor(context, null, nightMode) { changed() } }
		AlertDialog.Builder(ctx)
			.setTitle(R.string.bikes)
			.setView(view)
			.setPositiveButton(R.string.shared_string_close, null)
			.show()
	}

	/**
	 * Adds a bike ([bike] null) or edits one. A new bike gets the type of the current profile when that is a bike,
	 * and [usages] as its ways of use. [onDone] gets the saved bike, or null when it was deleted.
	 */
	@JvmStatic
	@JvmOverloads
	fun showBikeEditor(context: Context, bike: Bike?, nightMode: Boolean, usages: List<String>? = null,
	                   onDone: (Bike?) -> Unit) {
		val app = context.applicationContext as OsmandApplication
		val helper = BikeHelper(app)
		val ctx = UiUtilities.getThemedContext(context, nightMode)
		val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bike_battery, null, false)
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.description), false)
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.add_battery), false)
		val container = view.findViewById<LinearLayout>(R.id.values)

		val currentMode = app.settings.applicationMode
		var draft = bike ?: helper.createBike(
			(if (currentMode in RidingQuestions.ALL_BIKES) currentMode else ApplicationMode.SPEED_PEDELEC).stringKey,
			usages ?: listOf(Bike.USAGE_TRAVEL))
		// a new bike is named after its type until the user types a name
		var autoName = bike == null

		val nameLayout = addInput(ctx, container, app.getString(R.string.shared_string_name), null,
			InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
		val nameInput = nameLayout.editText!!
		nameInput.setText(draft.name)
		nameInput.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
			override fun afterTextChanged(s: Editable?) {
				if (nameInput.hasFocus()) {
					autoName = false
				}
			}
		})
		val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
		container.addView(rows)

		lateinit var fillRows: () -> Unit
		fillRows = {
			rows.removeAllViews()
			val mode = helper.getMode(draft)
			addRow(ctx, app, rows, nightMode, helper.getIconRes(draft), app.getString(R.string.bike_appearance),
				app.getString(if (draft.iconName == null && draft.color == null) R.string.bike_appearance_default
				else R.string.bike_appearance_custom), null, iconColor = helper.getColor(draft, nightMode)) {
				chooseAppearance(ctx, app, draft, nightMode) { iconName, color ->
					draft = draft.copy(iconName = iconName, color = color)
					fillRows()
				}
			}
			addRow(ctx, app, rows, nightMode, mode?.iconRes ?: R.drawable.ic_action_bicycle_dark,
				app.getString(R.string.bike_type), mode?.toHumanString() ?: draft.profileKey, null) {
				chooseType(ctx, draft.profileKey) { profileKey ->
					draft = draft.copy(profileKey = profileKey)
					if (autoName) {
						val garage = helper.getGarage().let { if (bike != null) it.deleteBike(bike) else it }
						val typeName = ApplicationMode.valueOfStringKey(profileKey, null)?.toHumanString() ?: profileKey
						nameInput.setText(garage.newBikeName(typeName))
					}
					fillRows()
				}
			}
			val riding = RidingUsage.fromKey(draft.usage)
			addRow(ctx, app, rows, nightMode, riding.iconId, app.getString(R.string.bike_riding),
				helper.formatUsages(draft.allUsages), null) {
				chooseUsages(ctx, draft.allUsages) { chosen ->
					draft = draft.withUsages(chosen)
					fillRows()
				}
			}
			if (helper.canHaveBattery(draft.profileKey)) {
				val batteries = helper.getGarage().batteriesOf(draft)
				addRow(ctx, app, rows, nightMode, R.drawable.ic_action_battery, app.getString(R.string.bike_batteries),
					if (batteries.isEmpty()) app.getString(R.string.bike_batteries_none_selected)
					else batteries.joinToString(" + ") { helper.formatBattery(it) }, null) {
					showBatteryPicker(context, nameInput.text.toString(), draft.batteryIds, nightMode) { ids ->
						draft = draft.copy(batteryIds = ids)
						fillRows()
					}
				}
				val defaultConsumption = helper.getDefaultConsumption(draft.profileKey)
				val consumption = draft.consumptionWhPerKm
				addRow(ctx, app, rows, nightMode, R.drawable.ic_action_speed, app.getString(R.string.bike_battery_consumption),
					if (consumption != null) helper.formatConsumption(consumption)
					else app.getString(R.string.ltr_or_rtl_combine_via_space, helper.formatConsumption(defaultConsumption),
						"(" + app.getString(R.string.shared_string_default) + ")"), null) {
					showConsumptionDialog(context, defaultConsumption, consumption, nightMode) { value ->
						draft = draft.copy(consumptionWhPerKm = value)
						fillRows()
					}
				}
			}
		}
		fillRows()

		val builder = AlertDialog.Builder(ctx)
			.setTitle(if (bike == null) R.string.bike_add else R.string.bike_edit)
			.setView(view)
			.setPositiveButton(R.string.shared_string_save) { _, _ ->
				val typeName = helper.getMode(draft)?.toHumanString() ?: draft.profileKey
				val saved = draft.copy(name = nameInput.text.toString().trim().ifEmpty { typeName })
				val riddenNow = helper.getCurrentBike()?.id == saved.id
				helper.saveGarage(helper.getGarage().saveBike(saved))
				helper.getMode(saved)?.let { mode ->
					if (mode !in ApplicationMode.values(app)) {
						ApplicationMode.changeProfileAvailability(mode, true, app)
					}
				}
				if (riddenNow && bike?.usage != saved.usage) {
					// the way of use in force was removed from this bike
					helper.selectBike(saved)
				}
				onDone(saved)
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
		if (bike != null) {
			builder.setNeutralButton(R.string.shared_string_delete) { _, _ ->
				AlertDialog.Builder(ctx)
					.setMessage(app.getString(R.string.bike_delete_confirm, bike.name))
					.setPositiveButton(R.string.shared_string_delete) { _, _ ->
						helper.saveGarage(helper.getGarage().deleteBike(bike))
						onDone(null)
					}
					.setNegativeButton(R.string.shared_string_cancel, null)
					.show()
			}
		}
		builder.show()
	}

	/** Which of the user's batteries a bike called [bikeName] uses; batteries can be added and edited here. */
	@JvmStatic
	fun showBatteryPicker(context: Context, bikeName: String, batteryIds: List<String>, nightMode: Boolean,
	                      onDone: (List<String>) -> Unit) {
		val app = context.applicationContext as OsmandApplication
		val helper = BikeHelper(app)
		val ctx = UiUtilities.getThemedContext(context, nightMode)
		val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bike_battery, null, false)
		view.findViewById<TextView>(R.id.description).text = app.getString(R.string.bike_batteries_descr, bikeName)
		val container = view.findViewById<LinearLayout>(R.id.values)
		val selected = batteryIds.toMutableList()

		lateinit var fill: () -> Unit
		fill = {
			container.removeAllViews()
			val garage = helper.getGarage()
			selected.retainAll(garage.batteries.map { it.id }.toSet())
			for (battery in garage.batteries) {
				val checked = battery.id in selected
				val row = addRow(ctx, app, container, nightMode, R.drawable.ic_action_battery, battery.name,
					helper.formatWh(battery.capacityWh), checked, multiple = true) {
					if (!selected.remove(battery.id)) {
						selected.add(battery.id)
					}
					fill()
				}
				row.setOnLongClickListener {
					showEditBatteryDialog(context, battery, nightMode) { fill() }
					true
				}
			}
			if (garage.batteries.isEmpty()) {
				addRow(ctx, app, container, nightMode, R.drawable.ic_action_battery,
					app.getString(R.string.bike_batteries_none), app.getString(R.string.bike_batteries_none_descr), null, onClick = null)
			} else {
				val hint = TextView(ctx)
				hint.setText(R.string.bike_batteries_edit_hint)
				hint.setTextColor(ColorUtilities.getSecondaryTextColor(app, nightMode))
				container.addView(hint)
			}
		}
		fill()
		view.findViewById<View>(R.id.add_battery).setOnClickListener {
			showEditBatteryDialog(context, null, nightMode) { created ->
				if (created != null) {
					selected.add(created.id)
				}
				fill()
			}
		}
		AlertDialog.Builder(ctx)
			.setTitle(R.string.bike_batteries)
			.setView(view)
			.setPositiveButton(R.string.shared_string_ok) { _, _ -> onDone(selected.toList()) }
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	/** Adds a battery ([battery] null) or edits one; [onDone] gets the saved battery, or null when it was deleted. */
	@JvmStatic
	fun showEditBatteryDialog(context: Context, battery: BikeBattery?, nightMode: Boolean,
	                          onDone: (BikeBattery?) -> Unit) {
		val app = context.applicationContext as OsmandApplication
		val helper = BikeHelper(app)
		val ctx = UiUtilities.getThemedContext(context, nightMode)
		val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bike_battery, null, false)
		view.findViewById<TextView>(R.id.description).setText(R.string.bike_battery_edit_descr)
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.add_battery), false)
		val container = view.findViewById<LinearLayout>(R.id.values)

		val nameLayout = addInput(ctx, container, app.getString(R.string.shared_string_name), null,
			InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
		val capacityLayout = addInput(ctx, container, app.getString(R.string.bike_battery_capacity),
			app.getString(R.string.unit_wh), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
		nameLayout.placeholderText = app.getString(R.string.bike_battery_name_hint)
		if (battery != null) {
			nameLayout.editText?.setText(battery.name)
			capacityLayout.editText?.setText(BikeHelper.formatNumber(battery.capacityWh))
		}

		val builder = AlertDialog.Builder(ctx)
			.setTitle(if (battery == null) R.string.bike_battery_add else R.string.bike_battery_edit)
			.setView(view)
			.setPositiveButton(R.string.shared_string_ok, null)
			.setNegativeButton(R.string.shared_string_cancel, null)
		if (battery != null) {
			builder.setNeutralButton(R.string.shared_string_delete) { _, _ ->
				val bikes = helper.getGarage().bikesUsing(battery).joinToString(", ") { it.name }
				val message = if (bikes.isEmpty()) {
					app.getString(R.string.bike_battery_delete_confirm, battery.name)
				} else {
					app.getString(R.string.bike_battery_delete_confirm_used, battery.name, bikes)
				}
				AlertDialog.Builder(ctx)
					.setMessage(message)
					.setPositiveButton(R.string.shared_string_delete) { _, _ ->
						helper.saveGarage(helper.getGarage().deleteBattery(battery))
						onDone(null)
					}
					.setNegativeButton(R.string.shared_string_cancel, null)
					.show()
			}
		}
		val dialog = builder.show()
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			val capacity = parseNumber(capacityLayout.editText?.text?.toString())
			if (capacity == null || capacity <= 0) {
				capacityLayout.error = app.getString(R.string.bike_battery_capacity_error)
				return@setOnClickListener
			}
			val name = nameLayout.editText?.text?.toString()?.trim().orEmpty()
				.ifEmpty { helper.formatWh(capacity) }
			val saved = battery?.copy(name = name, capacityWh = capacity) ?: helper.createBattery(name, capacity)
			helper.saveGarage(helper.getGarage().saveBattery(saved))
			dialog.dismiss()
			onDone(saved)
		}
	}

	/** Consumption of a bike; [onDone] gets null for the type's default. */
	@JvmStatic
	fun showConsumptionDialog(context: Context, defaultWhPerKm: Double, current: Double?, nightMode: Boolean,
	                          onDone: (Double?) -> Unit) {
		val app = context.applicationContext as OsmandApplication
		val helper = BikeHelper(app)
		val ctx = UiUtilities.getThemedContext(context, nightMode)
		val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bike_battery, null, false)
		view.findViewById<TextView>(R.id.description).text =
			app.getString(R.string.bike_battery_consumption_descr, helper.formatConsumption(defaultWhPerKm))
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.add_battery), false)
		val container = view.findViewById<LinearLayout>(R.id.values)
		val layout = addInput(ctx, container, app.getString(R.string.bike_battery_consumption),
			app.getString(R.string.unit_wh_per_km), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
		layout.placeholderText = BikeHelper.formatNumber(defaultWhPerKm)
		current?.let { layout.editText?.setText(BikeHelper.formatNumber(it)) }

		AlertDialog.Builder(ctx)
			.setTitle(R.string.bike_battery_consumption)
			.setView(view)
			.setPositiveButton(R.string.shared_string_ok) { _, _ ->
				onDone(parseNumber(layout.editText?.text?.toString())?.takeIf { it > 0 })
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setNeutralButton(R.string.shared_string_revert) { _, _ -> onDone(null) }
			.show()
	}

	private fun chooseType(ctx: Context, profileKey: String, onChosen: (String) -> Unit) {
		val types = RidingQuestions.ALL_BIKES
		val names = types.map { it.toHumanString() }.toTypedArray()
		AlertDialog.Builder(ctx)
			.setTitle(R.string.bike_type)
			.setSingleChoiceItems(names, types.indexOfFirst { it.stringKey == profileKey }) { dialog, which ->
				dialog.dismiss()
				onChosen(types[which].stringKey)
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	/** Ways of use of a bike; at least one has to stay ticked. */
	private fun chooseUsages(ctx: Context, usages: List<String>, onChosen: (List<String>) -> Unit) {
		val all = RidingUsage.entries
		val names = all.map { ctx.getString(it.titleId) }.toTypedArray()
		val checked = BooleanArray(all.size) { all[it].key in usages }
		lateinit var dialog: AlertDialog
		dialog = AlertDialog.Builder(ctx)
			.setTitle(R.string.bike_riding)
			.setMultiChoiceItems(names, checked) { _, which, isChecked ->
				checked[which] = isChecked
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
			}
			.setPositiveButton(R.string.shared_string_ok) { _, _ ->
				onChosen(all.filterIndexed { index, _ -> checked[index] }.map { it.key })
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	/**
	 * Symbol and colour of a bike. [onChosen] gets null for both to follow the bike's type: its icon and the
	 * colour of its profile.
	 */
	private fun chooseAppearance(ctx: Context, app: OsmandApplication, bike: Bike, nightMode: Boolean,
	                             onChosen: (String?, Int?) -> Unit) {
		val helper = BikeHelper(app)
		var iconName = bike.iconName
		var color = bike.color
		val density = ctx.resources.displayMetrics.density
		val cellSize = (48 * density).toInt()
		val padding = ctx.resources.getDimensionPixelSize(R.dimen.dialog_content_margin)
		val content = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, padding / 2, padding, 0)
		}

		lateinit var fill: () -> Unit
		fill = {
			content.removeAllViews()
			val shown = bike.copy(iconName = iconName, color = color)
			val selectedIcon = helper.getIconRes(shown)
			val selectedColor = helper.getColor(shown, nightMode)
			addHeader(ctx, app, content, nightMode, R.string.bike_symbol)
			val icons = BikeHelper.BIKE_ICONS.map { iconId ->
				val cell = ImageView(ctx)
				cell.layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
				cell.scaleType = ImageView.ScaleType.CENTER
				cell.setImageDrawable(app.uiUtilities.getPaintedIcon(iconId,
					if (iconId == selectedIcon) selectedColor else ColorUtilities.getDefaultIconColor(app, nightMode)))
				if (iconId == selectedIcon) {
					cell.background = circle(ColorUtilities.getColorWithAlpha(selectedColor, 0.2f), null, density)
				}
				cell.contentDescription = app.resources.getResourceEntryName(iconId)
				cell.setOnClickListener {
					iconName = app.resources.getResourceEntryName(iconId)
					fill()
				}
				cell
			}
			addGrid(ctx, content, icons)
			addHeader(ctx, app, content, nightMode, R.string.bike_color)
			val colors = BikeHelper.BIKE_COLORS.map { value ->
				val cell = View(ctx)
				val inset = (6 * density).toInt()
				cell.layoutParams = LinearLayout.LayoutParams(cellSize - 2 * inset, cellSize - 2 * inset)
					.apply { setMargins(inset, inset, inset, inset) }
				val ring = if (value == selectedColor) ColorUtilities.getPrimaryTextColor(app, nightMode) else null
				cell.background = circle(value, ring, density)
				cell.setOnClickListener {
					color = value
					fill()
				}
				cell
			}
			addGrid(ctx, content, colors)
		}
		fill()

		AlertDialog.Builder(ctx)
			.setTitle(R.string.bike_appearance)
			.setView(android.widget.ScrollView(ctx).apply { addView(content) })
			.setPositiveButton(R.string.shared_string_ok) { _, _ -> onChosen(iconName, color) }
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setNeutralButton(R.string.bike_appearance_default) { _, _ -> onChosen(null, null) }
			.show()
	}

	private fun addHeader(ctx: Context, app: OsmandApplication, container: ViewGroup, nightMode: Boolean,
	                      titleId: Int) {
		val header = TextView(ctx)
		header.setText(titleId)
		header.setTextColor(ColorUtilities.getSecondaryTextColor(app, nightMode))
		header.setPadding(0, (8 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
		container.addView(header)
	}

	/** Lays [cells] out in rows of six. */
	private fun addGrid(ctx: Context, container: ViewGroup, cells: List<View>) {
		for (rowCells in cells.chunked(6)) {
			val row = LinearLayout(ctx)
			row.orientation = LinearLayout.HORIZONTAL
			rowCells.forEach { row.addView(it) }
			container.addView(row)
		}
	}

	private fun circle(fill: Int, ring: Int?, density: Float): GradientDrawable =
		GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			setColor(fill)
			if (ring != null) {
				setStroke((3 * density).toInt(), ring)
			}
		}

	/** A row with icon, title and description; [selected] null: no radio button or check box. */
	private fun addRow(ctx: Context, app: OsmandApplication, container: ViewGroup, nightMode: Boolean,
	                   @DrawableRes iconId: Int, title: String, description: String, selected: Boolean?,
	                   multiple: Boolean = false, iconColor: Int? = null, onClick: (() -> Unit)?): View {
		val row = LayoutInflater.from(ctx).inflate(R.layout.first_usage_option_item, container, false)
		val activeColor = ColorUtilities.getActiveColor(app, nightMode)
		val color = iconColor
			?: if (selected == true) activeColor else ColorUtilities.getDefaultIconColor(app, nightMode)
		row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.uiUtilities.getPaintedIcon(iconId, color))
		row.findViewById<TextView>(R.id.title).text = title
		row.findViewById<TextView>(R.id.description).text = description
		val radio = row.findViewById<CompoundButton>(R.id.compound_button)
		val checkBox = row.findViewById<CompoundButton>(R.id.check_box)
		val button = if (multiple) checkBox else radio
		UiUtilities.setupCompoundButton(nightMode, activeColor, button)
		button.isChecked = selected == true
		button.visibility = if (selected == null) View.GONE else View.VISIBLE
		(if (multiple) radio else checkBox).visibility = View.GONE
		if (onClick != null) {
			row.setOnClickListener { onClick() }
		} else {
			row.background = null
		}
		container.addView(row)
		return row
	}

	private fun addInput(ctx: Context, container: LinearLayout, hint: String, suffix: String?,
	                     inputType: Int): TextInputLayout {
		val layout = LayoutInflater.from(ctx)
			.inflate(R.layout.item_bike_battery_value, container, false) as TextInputLayout
		layout.hint = hint
		layout.suffixText = suffix
		layout.findViewById<TextInputEditText>(R.id.input).inputType = inputType
		container.addView(layout)
		return layout
	}

	private fun parseNumber(text: String?): Double? =
		text?.trim()?.replace(',', '.')?.toDoubleOrNull()
}
