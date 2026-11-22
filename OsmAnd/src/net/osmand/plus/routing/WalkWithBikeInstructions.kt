package net.osmand.plus.routing

import android.content.Context
import net.osmand.plus.R
import net.osmand.router.RouteSegmentResult
import net.osmand.router.TurnType
import net.osmand.util.Algorithms

/**
 * Texts for walking beside the bike where it may not be ridden (pedestrian zones, footways, stairs, ...).
 * RouteResultPreparation.markWalkingWithBike marks the turns where a walk starts and ends.
 */
object WalkWithBikeInstructions {

	/** Name of the street where the rider gets back on the bike, or null when the walk ends at the destination. */
	@JvmStatic
	fun findWalkToName(list: List<RouteSegmentResult>, walkStart: Int, lang: String, transliterate: Boolean): String? {
		for (i in walkStart + 1 until list.size) {
			if (list[i].turnType?.isWalkEnd == true) {
				val name = list[i].getStreetName(lang, transliterate, list, i)
				return if (Algorithms.isEmpty(name)) "" else name
			}
		}
		return null
	}

	/** "Step off and walk to Mechelsestraat", "Get back on your bike", or null for other turns. */
	@JvmStatic
	fun getPhrase(ctx: Context, info: RouteDirectionInfo): String? {
		val turn = info.turnType
		return when {
			turn.isWalkStartStairs -> ctx.getString(R.string.walk_with_bike_stairs)
			turn.isWalkStart -> {
				val walkTo = info.walkToName
				when {
					walkTo == null -> ctx.getString(R.string.walk_with_bike_to_destination)
					walkTo.isEmpty() -> ctx.getString(R.string.walk_with_bike)
					else -> ctx.getString(R.string.walk_with_bike_to, walkTo)
				}
			}
			turn.isWalkEnd -> ctx.getString(R.string.walk_with_bike_end)
			else -> null
		}
	}

	/** Turn description with the walking phrase; a plain "go straight" is replaced by the phrase alone. */
	@JvmStatic
	fun describe(ctx: Context, info: RouteDirectionInfo, description: String): String {
		val phrase = getPhrase(ctx, info) ?: return description
		val turn = info.turnType
		return if (turn.value == TurnType.C || description.isEmpty()) {
			phrase
		} else {
			ctx.getString(R.string.ltr_or_rtl_combine_via_dash, description, phrase)
		}
	}
}
