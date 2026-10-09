package net.osmand.plus.resources

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import net.osmand.IndexConstants
import net.osmand.plus.BuildConfig
import net.osmand.plus.OsmandApplication
import java.io.File

/**
 * Read-only access to the maps of another OsmAnd app on the same device, so that a build with its own
 * application id does not need a second copy of every map.
 *
 * Since Android 11 the Android/data folders of other apps are off limits, even with "All files access".
 * Android/obb is the exception: apps allowed to install other apps ("Install unknown apps") can read the
 * obb folders of other apps. OsmAnd keeps its data there when its data storage folder is set to
 * "Multiuser storage".
 */
object SharedMapsHelper {

	private val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

	@JvmStatic
	fun isSupported(): Boolean = BuildConfig.SHARED_OSMAND_MAPS

	/** True when Android still has to allow this app to read the obb folders of other apps. */
	@JvmStatic
	fun needsAccess(app: OsmandApplication): Boolean {
		return isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
				&& !app.packageManager.canRequestPackageInstalls()
	}

	@JvmStatic
	fun getAccessSettingsIntent(app: OsmandApplication): Intent {
		return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
	}

	/** Data folders of other OsmAnd apps that this app can read. */
	@JvmStatic
	fun getSharedDataDirs(app: OsmandApplication): List<File> {
		if (!isSupported()) {
			return emptyList()
		}
		val obbRoot = app.obbDir?.parentFile ?: return emptyList()
		return OSMAND_PACKAGES
			.filter { it != app.packageName }
			.map { File(obbRoot, it) }
			.filter { it.isDirectory && it.canRead() }
	}

	/**
	 * Map files in [subdir] of the shared data folders ("" for the data folder itself). Files that this app
	 * has in the same folder of its own storage, or that an earlier shared folder already provides, are
	 * left out so that no map is loaded twice.
	 */
	@JvmStatic
	fun collectMaps(app: OsmandApplication, subdir: String): List<File> {
		val result = mutableListOf<File>()
		val names = mutableSetOf<String>()
		val ownDir = app.getAppPath(subdir)
		for (dataDir in getSharedDataDirs(app)) {
			val files = File(dataDir, subdir).listFiles() ?: continue
			for (file in files) {
				val name = file.name
				if (file.isFile && name.endsWith(IndexConstants.BINARY_MAP_INDEX_EXT)
					&& !File(ownDir, name).exists() && names.add(name)) {
					result.add(file)
				}
			}
		}
		return result
	}
}
