package net.osmand.plus.firstusage

import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.RestartActivity
import net.osmand.plus.resources.SharedMapsHelper
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.widgets.dialogbutton.DialogButton

/**
 * Maps step of the first usage wizard when an OsmAnd app is installed (SharedMapsHelper): shows that its maps
 * are in use, or the steps to use them, in place of a map download.
 */
class SharedMapsCard(
	private val app: OsmandApplication,
	private val activity: FragmentActivity,
	view: View,
	private val nightMode: Boolean,
	private val onShowMap: Runnable,
	private val onDownloadInstead: Runnable
) {

	private val card: View = view.findViewById(R.id.shared_maps_card)
	private val icon: ImageView = card.findViewById(R.id.shared_maps_icon)
	private val title: TextView = card.findViewById(R.id.shared_maps_title)
	private val description: TextView = card.findViewById(R.id.shared_maps_description)
	private val accessStep: View = card.findViewById(R.id.shared_maps_step_access)
	private val storageStep: View = card.findViewById(R.id.shared_maps_step_storage)
	private val goButton: DialogButton = card.findViewById(R.id.shared_maps_go_button)
	private val downloadButton: DialogButton = card.findViewById(R.id.shared_maps_download_button)

	private var downloadInstead = false
	private var leftForSetup = false
	private var accessWasNeeded = false

	init {
		goButton.setOnClickListener { onShowMap.run() }
		downloadButton.setOnClickListener {
			downloadInstead = true
			update()
			onDownloadInstead.run()
		}
	}

	/** True when the card takes the place of the map download. */
	fun isShown(): Boolean = !downloadInstead && SharedMapsHelper.getInstalledOsmAndPackage(app) != null

	/** True when this app reads the maps of OsmAnd: there is nothing to download. */
	fun isInUse(): Boolean = isShown() && !SharedMapsHelper.needsAccess(app) && SharedMapsHelper.hasMaps(app)

	fun update() {
		val shown = isShown()
		card.visibility = if (shown) View.VISIBLE else View.GONE
		if (!shown) {
			return
		}
		val appName = app.getString(R.string.app_name)
		val activeColor = ColorUtilities.getActiveIconColor(app, nightMode)
		if (isInUse()) {
			val count = SharedMapsHelper.collectMaps(app, "").size +
					SharedMapsHelper.collectMaps(app, IndexConstants.ROADS_INDEX_DIR).size
			icon.setImageDrawable(app.uiUtilities.getPaintedIcon(R.drawable.ic_action_done, activeColor))
			title.setText(R.string.shared_osmand_maps_in_use_title)
			description.text = app.getString(R.string.shared_osmand_maps_in_use_descr, appName, count)
			accessStep.visibility = View.GONE
			storageStep.visibility = View.GONE
			goButton.visibility = View.VISIBLE
			downloadButton.visibility = View.GONE
			return
		}
		icon.setImageDrawable(app.uiUtilities.getPaintedIcon(R.drawable.ic_map, activeColor))
		title.setText(R.string.shared_osmand_maps_setup_title)
		description.text = app.getString(R.string.shared_osmand_maps_setup_descr, appName)
		goButton.visibility = View.GONE
		downloadButton.visibility = View.VISIBLE

		// Before Android 11 the obb folders of other apps can be read without asking
		val accessAsked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
		accessStep.visibility = if (accessAsked) View.VISIBLE else View.GONE
		setupStep(accessStep, 1, !SharedMapsHelper.needsAccess(app),
			app.getString(R.string.shared_osmand_maps_step_access, appName),
			app.getString(R.string.shared_osmand_maps_step_access_descr, appName),
			R.string.shared_osmand_maps_allow_access) {
			leaveForSetup(true)
			AndroidUtils.startActivityIfSafe(activity, SharedMapsHelper.getAccessSettingsIntent(app))
		}
		val osmandPackage = SharedMapsHelper.getInstalledOsmAndPackage(app)
		val launchIntent = osmandPackage?.let { app.packageManager.getLaunchIntentForPackage(it) }
		setupStep(storageStep, if (accessAsked) 2 else 1, false,
			app.getString(R.string.shared_osmand_maps_step_storage, appName),
			app.getString(R.string.shared_osmand_maps_step_storage_descr),
			R.string.shared_osmand_maps_open_osmand) {
			if (launchIntent != null) {
				leaveForSetup(false)
				AndroidUtils.startActivityIfSafe(activity, launchIntent)
			}
		}
	}

	private fun setupStep(step: View, number: Int, done: Boolean, title: String, description: String,
	                      buttonTitleId: Int, onClick: () -> Unit) {
		step.findViewById<TextView>(R.id.step_number).apply {
			text = number.toString()
			visibility = if (done) View.GONE else View.VISIBLE
		}
		step.findViewById<View>(R.id.step_done).visibility = if (done) View.VISIBLE else View.GONE
		step.findViewById<TextView>(R.id.step_title).text = title
		step.findViewById<TextView>(R.id.step_description).text = description
		step.findViewById<DialogButton>(R.id.step_button).apply {
			visibility = if (done) View.GONE else View.VISIBLE
			setTitleId(buttonTitleId)
			setOnClickListener { onClick() }
		}
	}

	private fun leaveForSetup(accessWasNeeded: Boolean) {
		leftForSetup = true
		this.accessWasNeeded = accessWasNeeded
	}

	/**
	 * Call from onResume: restarts the app when the user comes back with access allowed or with OsmAnd's maps
	 * moved. Android applies access to the obb folders of other apps only to a newly started process, and the
	 * app loads the maps at start. Otherwise shows how far the setup got.
	 */
	fun onResume() {
		if (leftForSetup) {
			leftForSetup = false
			if (!SharedMapsHelper.needsAccess(app) && (accessWasNeeded || SharedMapsHelper.hasMaps(app))) {
				RestartActivity.doRestart(activity,
					app.getString(R.string.shared_osmand_maps_restart, app.getString(R.string.app_name)))
				return
			}
		}
		update()
	}
}
