package net.osmand.plus.resources

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import net.osmand.IndexConstants
import net.osmand.PlatformUtil
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

	private val LOG = PlatformUtil.getLog(SharedMapsHelper::class.java)
	private val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
	private const val LINKS_DIR = "shared_maps"

	@JvmStatic
	fun isSupported(): Boolean = BuildConfig.SHARED_OSMAND_MAPS

	/** True when Android still has to allow this app to read the obb folders of other apps. */
	@JvmStatic
	fun needsAccess(app: OsmandApplication): Boolean {
		return isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
				&& !app.packageManager.canRequestPackageInstalls()
	}

	/** Package of an installed OsmAnd app whose maps this app could use, null if there is none. */
	@JvmStatic
	fun getInstalledOsmAndPackage(app: OsmandApplication): String? {
		if (!isSupported()) {
			return null
		}
		return OSMAND_PACKAGES.filter { it != app.packageName }.firstOrNull { packageName ->
			try {
				app.packageManager.getPackageInfo(packageName, 0)
				true
			} catch (e: PackageManager.NameNotFoundException) {
				false
			}
		}
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

	/** True when the shared data folders have maps that this app does not have itself. */
	@JvmStatic
	fun hasMaps(app: OsmandApplication): Boolean {
		return collectMaps(app, "").isNotEmpty() || collectMaps(app, IndexConstants.ROADS_INDEX_DIR).isNotEmpty()
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

	/**
	 * A folder of this app with links to the maps of [collectMaps] in [subdirs], for OsmAndCore, or null when there
	 * are none. Its ObfsCollection must not get single files (addFile): it creates its index cache only when the
	 * first of its sources is a folder, and their order is random, so a file source crashes it now and then.
	 */
	@JvmStatic
	fun linkMaps(app: OsmandApplication, name: String, vararg subdirs: String): File? {
		val dir = File(File(app.noBackupFilesDir, LINKS_DIR), name)
		// Deleting a link leaves the map it points to alone
		dir.listFiles()?.forEach { it.delete() }
		val maps = subdirs.flatMap { collectMaps(app, it) }
		if (maps.isEmpty() || !(dir.isDirectory || dir.mkdirs())) {
			return null
		}
		for (map in maps) {
			try {
				Os.symlink(map.absolutePath, File(dir, map.name).absolutePath)
			} catch (e: ErrnoException) {
				LOG.error("Could not link shared map " + map.absolutePath, e)
			}
		}
		return dir
	}
}
