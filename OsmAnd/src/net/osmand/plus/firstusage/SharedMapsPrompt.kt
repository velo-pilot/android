package net.osmand.plus.firstusage

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.RestartActivity
import net.osmand.plus.resources.SharedMapsHelper
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.UiUtilities

/**
 * Before the first map download: when an OsmAnd app is installed, offers to use its maps instead
 * (SharedMapsHelper), either by allowing access to them or by moving them to OsmAnd's Multiuser storage.
 */
class SharedMapsPrompt(private val app: OsmandApplication) {

	private var leftForSetup = false
	private var accessWasNeeded = false

	/** True when an OsmAnd app is installed whose maps this app cannot use yet. */
	fun isNeeded(): Boolean {
		return SharedMapsHelper.getInstalledOsmAndPackage(app) != null
				&& (SharedMapsHelper.needsAccess(app) || !SharedMapsHelper.hasMaps(app))
	}

	fun show(activity: FragmentActivity, nightMode: Boolean, onDownload: Runnable) {
		val osmandPackage = SharedMapsHelper.getInstalledOsmAndPackage(app) ?: return
		val needsAccess = SharedMapsHelper.needsAccess(app)
		val appName = app.getString(R.string.app_name)
		val builder = AlertDialog.Builder(UiUtilities.getThemedContext(activity, nightMode))
			.setNegativeButton(R.string.shared_string_download) { _, _ -> onDownload.run() }
		if (needsAccess) {
			builder.setTitle(R.string.shared_osmand_maps_prompt_title)
				.setMessage(app.getString(R.string.shared_osmand_maps_prompt_no_access, appName))
				.setPositiveButton(R.string.shared_osmand_maps_allow_access) { _, _ ->
					leaveForSetup(true)
					AndroidUtils.startActivityIfSafe(activity, SharedMapsHelper.getAccessSettingsIntent(app))
				}
		} else {
			builder.setTitle(R.string.shared_osmand_maps_prompt_move_title)
				.setMessage(app.getString(R.string.shared_osmand_maps_prompt_not_found, appName))
			val launchIntent = app.packageManager.getLaunchIntentForPackage(osmandPackage)
			if (launchIntent != null) {
				builder.setPositiveButton(R.string.shared_osmand_maps_open_osmand) { _, _ ->
					leaveForSetup(false)
					AndroidUtils.startActivityIfSafe(activity, launchIntent)
				}
			}
		}
		builder.show()
	}

	private fun leaveForSetup(accessWasNeeded: Boolean) {
		leftForSetup = true
		this.accessWasNeeded = accessWasNeeded
	}

	/**
	 * Call from onResume: restarts the app when the user comes back with access allowed or with OsmAnd's maps
	 * moved. Android applies access to the obb folders of other apps only to a newly started process, and the
	 * app loads the maps at start.
	 */
	fun onResume(activity: FragmentActivity) {
		if (!leftForSetup) {
			return
		}
		leftForSetup = false
		if (!SharedMapsHelper.needsAccess(app) && (accessWasNeeded || SharedMapsHelper.hasMaps(app))) {
			RestartActivity.doRestart(activity, app.getString(R.string.shared_osmand_maps_restart, app.getString(R.string.app_name)))
		}
	}
}
