package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.router.GeneralRouter.RouteDataObjectAttribute;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;
import net.osmand.shared.routing.GeneralRouterProfile;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cargo bike, road bike, mountain bike, velomobile, trike and e-scooter profiles (router_profiles/*.xml), which
 * extend the bicycle profile (the e-scooter through the e-bike profile).
 */
public class OtherBikeProfilesTest {

	private static final String[] PROFILES = {"cargo_bike", "road_bike", "mountain_bike", "velomobile", "trike", "e_scooter"};
	private static final String[] WIDE_PROFILES = {"cargo_bike", "velomobile", "trike"};

	private static RoutingConfiguration.Builder builder;

	@BeforeClass
	public static void setUp() {
		builder = RoutingConfiguration.parseDefault();
	}

	@Test
	public void testProfilesExtendBicycle() {
		GeneralRouter bicycle = builder.getRouter("bicycle");
		for (String name : PROFILES) {
			GeneralRouter router = builder.getRouter(name);
			Assert.assertNotNull(name, router);
			Assert.assertEquals(name, GeneralRouterProfile.BICYCLE, router.getProfile());
			Assert.assertEquals(name, router.getProfileName());
			Assert.assertTrue(name, router.getParameters().keySet().containsAll(bicycle.getParameters().keySet()));
			Assert.assertTrue(name, router.getParameters().containsKey("node_network_prefer"));
			Assert.assertFalse(name, router(name).isHHRoutingAllowed());
			// rules the profile doesn't change come from the bicycle profile
			Map<String, String> cycleway = tags("highway", "cycleway", "surface", "asphalt");
			Assert.assertEquals(name, priority("bicycle", cycleway), priority(name, cycleway), 0.001);
			Assert.assertEquals(name, 1, access(name, cycleway));
		}
		Assert.assertEquals("car", builder.getDefaultRouter());
		Assert.assertEquals(builder.getRouter("e_bike").getParameters().keySet(),
				builder.getRouter("e_scooter").getParameters().keySet());
		Assert.assertTrue(builder.getRouter("cargo_bike").getParameters().containsKey("electric_assist"));
		Assert.assertEquals(18, builder.getRouter("cargo_bike").getDefaultSpeed() * 3.6, 0.01);
		Assert.assertEquals(30, builder.getRouter("velomobile").getDefaultSpeed() * 3.6, 0.01);
	}

	@Test
	public void testWideBikes() {
		Map<String, String> narrow = tags("barrier", "bollard", "maxwidth", "0.8");
		Map<String, String> wide = tags("barrier", "bollard", "maxwidth", "1.2");
		for (String name : WIDE_PROFILES) {
			Assert.assertEquals(name, -1, access(name, tags("highway", "steps")));
			Assert.assertEquals(name, -1, obstacle(name, tags("barrier", "kissing_gate")), 0.001);
			Assert.assertTrue(name, obstacle(name, tags("barrier", "cycle_barrier")) >= 300);
			Assert.assertEquals(name, -1, obstacle(name, narrow, "width", "0.9"), 0.001);
			Assert.assertEquals(name, 0, obstacle(name, wide, "width", "0.9"), 0.001);
			Assert.assertEquals(name, 0, obstacle(name, narrow), 0.001);
			// the way width of the bicycle profile
			Assert.assertEquals(name, -1, access(name, tags("highway", "cycleway", "width", "0.7"), "width", "0.9"));
			Assert.assertTrue(name, priority(name, tags("highway", "residential", "surface", "sett"))
					< priority("bicycle", tags("highway", "residential", "surface", "sett")));
		}
		Assert.assertEquals(1, access("bicycle", tags("highway", "steps")));
		Assert.assertEquals(0, obstacle("bicycle", narrow, "width", "0.9"), 0.001);
	}

	@Test
	public void testClimbs() {
		Assert.assertEquals(16, climbPenalty("bicycle", 10), 0.001);
		Assert.assertEquals(6, climbPenalty("cargo_bike", 10, "electric_assist"), 0.001);
		Assert.assertEquals(24, climbPenalty("cargo_bike", 10), 0.001);
		Assert.assertEquals(32, climbPenalty("trike", 10), 0.001);
		Assert.assertEquals(8, climbPenalty("trike", 5), 0.001);
		Assert.assertEquals(6, climbPenalty("e_scooter", 10), 0.001);
		Assert.assertEquals(climbPenalty("bicycle", 10, "relief_smoothness_factor_hills"),
				climbPenalty("cargo_bike", 10, "relief_smoothness_factor_hills"), 0.001);
	}

	@Test
	public void testSurfaces() {
		Map<String, String> gravelTrack = tags("highway", "track", "surface", "gravel");
		Map<String, String> cobbles = tags("highway", "residential", "surface", "cobblestone");
		Map<String, String> asphalt = tags("highway", "residential", "surface", "asphalt");
		Assert.assertTrue(priority("road_bike", gravelTrack) < priority("bicycle", gravelTrack));
		Assert.assertTrue(priority("road_bike", cobbles) < priority("bicycle", cobbles));
		Assert.assertTrue(priority("e_scooter", gravelTrack) <= 0.05f);
		Assert.assertTrue(priority("mountain_bike", gravelTrack) > priority("bicycle", gravelTrack));
		Assert.assertTrue(priority("mountain_bike", gravelTrack) > priority("mountain_bike", asphalt));
		Assert.assertTrue(priority("mountain_bike", tags("highway", "path", "mtb:scale", "4"))
				< priority("mountain_bike", tags("highway", "path", "mtb:scale", "1")));
		Assert.assertEquals(priority("bicycle", asphalt), priority("road_bike", asphalt), 0.001);
	}

	private static float priority(String profile, Map<String, String> tags, String... params) {
		return router(profile, params).defineSpeedPriority(way(tags), true);
	}

	private static int access(String profile, Map<String, String> tags, String... params) {
		return router(profile, params).getObjContext(RouteDataObjectAttribute.ACCESS).evaluateInt(way(tags), 0);
	}

	// The routing obstacle of a node with these tags in the middle of a residential street
	private static float obstacle(String profile, Map<String, String> nodeTags, String... params) {
		Map<String, String> all = tags("highway", "residential");
		all.putAll(nodeTags);
		RouteDataObject way = way(all);
		int[] wayTypes = new int[] {way.types[0]};
		int[] pointTypes = new int[way.types.length - 1];
		System.arraycopy(way.types, 1, pointTypes, 0, pointTypes.length);
		way.types = wayTypes;
		way.pointTypes = new int[][] {null, pointTypes};
		return router(profile, params).defineRoutingObstacle(way, 1, false);
	}

	private static float climbPenalty(String profile, double incline, String... params) {
		String[] all = new String[params.length + 1];
		System.arraycopy(params, 0, all, 0, params.length);
		all[params.length] = GeneralRouter.USE_HEIGHT_OBSTACLES;
		GeneralRouter router = router(profile, all);
		// a way rising 1 m every 100 m per percent of incline: the penalty is per metre of climb
		RouteDataObject way = way(tags("highway", "residential"));
		way.pointsX = new int[] {0, 0};
		way.pointsY = new int[] {0, 10000};
		way.heightDistanceArray = new float[] {0, 0, 100, (float) incline};
		return (float) (router.defineHeightObstacle(way, (short) 0, (short) 1) / incline);
	}

	// Parameters are names of boolean parameters, or name and value pairs for numeric ones
	private static GeneralRouter router(String profile, String... params) {
		Map<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < params.length; i++) {
			if (i + 1 < params.length && Character.isDigit(params[i + 1].charAt(0))) {
				values.put(params[i], params[++i]);
			} else {
				values.put(params[i], "true");
			}
		}
		return builder.build(profile, new RoutingMemoryLimits(30, 256), values).router;
	}

	private static Map<String, String> tags(String... tagValues) {
		Map<String, String> tags = new LinkedHashMap<>();
		for (int i = 0; i < tagValues.length; i += 2) {
			tags.put(tagValues[i], tagValues[i + 1]);
		}
		return tags;
	}

	private static RouteDataObject way(Map<String, String> tags) {
		RouteRegion region = new RouteRegion();
		int[] types = new int[tags.size()];
		int id = 0;
		for (Map.Entry<String, String> e : tags.entrySet()) {
			region.initRouteEncodingRule(id, e.getKey(), e.getValue());
			types[id] = id;
			id++;
		}
		RouteDataObject way = new RouteDataObject(region);
		way.types = types;
		way.pointsX = new int[] {0, 100, 200};
		way.pointsY = new int[] {0, 100, 200};
		return way;
	}
}
