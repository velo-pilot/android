package net.osmand.router;

import static net.osmand.router.SpeedPedelecProfileTest.tags;
import static net.osmand.router.SpeedPedelecProfileTest.way;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;
import net.osmand.router.GeneralRouter.RouteDataObjectAttribute;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walking beside the bike where riding is not allowed (parameter allow_walking_with_bike, which the app passes
 * unless the rider switches it off). The walking rule set is repeated in several sections of speed_pedelec.xml
 * and router_profiles/bicycle.xml; this test checks that the copies agree, that walking only replaces riding
 * where riding is not allowed, and that RouteResultPreparation.markWalkingWithBike finds the walks.
 */
public class WalkingWithBikeTest {

	static final String ALLOW_WALKING = RouteResultPreparation.ALLOW_WALKING_WITH_BIKE;
	private static final float WALKING_TRANSITION = 20;

	private static final String SPEED_PEDELEC = "speed_pedelec";
	private static final double PEDELEC_WALKING_KMH = 5;
	private static final float PEDELEC_WALKING_PRIORITY = 0.8f;
	private static final String[][] PEDELEC_PARAM_SETS = {
			{},
			{"moped_rules"},
			{"allow_pedestrian_zones"},
			{"allow_closed_to_vehicles"},
			{"avoid_closed_to_vehicles"},
			{"avoid_moped_no_cycleways"},
			{"allow_bicycle_contraflow"},
	};

	private static final String[] BICYCLES = {"bicycle", "e_bike", "cargo_bike", "road_bike", "mountain_bike",
			"velomobile", "trike", "e_scooter"};
	private static final double BICYCLE_WALKING_KMH = 4;
	private static final String[][] BICYCLE_PARAM_SETS = {
			{},
			{"driving_style_speed"},
			{"driving_style_safety"},
			{"driving_style_leisure"},
			{"node_network_prefer"},
			{"avoid_footways"},
			{"avoid_unpaved"},
	};

	private static RoutingConfiguration.Builder builder;

	@BeforeClass
	public static void setUp() {
		builder = RoutingConfiguration.parseDefault();
	}

	@Test
	public void testWalkingSpeedAttribute() {
		Assert.assertEquals(PEDELEC_WALKING_KMH, walkingSpeed(SPEED_PEDELEC), 0.001);
		for (String profile : BICYCLES) {
			Assert.assertEquals(profile, BICYCLE_WALKING_KMH, walkingSpeed(profile), 0.001);
		}
	}

	@Test
	public void testDefaultOn() {
		for (String profile : with(BICYCLES, SPEED_PEDELEC)) {
			GeneralRouter.RoutingParameter param = router(profile).getParameters().get(ALLOW_WALKING);
			Assert.assertNotNull(profile, param);
			Assert.assertTrue(profile, param.getDefaultBoolean());
		}
	}

	// ---------------------------------------------------------------- speed pedelec

	@Test
	public void testPedelecWalksWhereRidingIsNotAllowed() {
		assertPedelecWalk(tags("highway", "pedestrian", "bicycle", "yes", "oneway", "yes"));
		assertPedelecWalk(tags("highway", "footway"));
		assertPedelecWalk(tags("highway", "footway", "footway", "sidewalk"));
		assertPedelecWalk(tags("highway", "cycleway", "foot", "designated", "segregated", "no"));
		// C3 street open to buses and bicycles, off a signed cycle route
		assertPedelecWalk(tags("highway", "residential", "motor_vehicle", "no", "bicycle", "yes", "oneway", "yes"));
		assertPedelecWalk(tags("highway", "path"), "moped_rules");
	}

	@Test
	public void testPedelecRidesWhereAllowed() {
		assertRide(SPEED_PEDELEC, PEDELEC_WALKING_KMH, tags("highway", "pedestrian", "bicycle", "yes"), "allow_pedestrian_zones");
		assertRide(SPEED_PEDELEC, PEDELEC_WALKING_KMH, tags("highway", "footway", "bicycle", "designated", "segregated", "yes"));
		assertRide(SPEED_PEDELEC, PEDELEC_WALKING_KMH, tags("highway", "residential", "motor_vehicle", "no", "bicycle", "yes", "route_bicycle", "yes"));
		assertRide(SPEED_PEDELEC, PEDELEC_WALKING_KMH, tags("highway", "cycleway", "motor_vehicle", "no"));
		assertRide(SPEED_PEDELEC, PEDELEC_WALKING_KMH, tags("highway", "residential", "maxspeed", "5"));
	}

	@Test
	public void testPedelecWalkingOff() {
		Assert.assertEquals(-1, access(router(SPEED_PEDELEC), way(tags("highway", "pedestrian", "bicycle", "yes"))));
		Assert.assertEquals(-1, access(router(SPEED_PEDELEC), way(tags("highway", "footway"))));
	}

	@Test
	public void testPedelecNeverWalks() {
		GeneralRouter r = router(SPEED_PEDELEC, ALLOW_WALKING);
		Assert.assertEquals(-1, access(r, way(tags("highway", "steps"))));
		Assert.assertEquals(-1, access(r, way(tags("highway", "footway", "foot", "no"))));
		Assert.assertEquals(-1, access(r, way(tags("highway", "footway", "access", "private"))));
		Assert.assertEquals(-1, access(r, way(tags("highway", "motorway"))));
		Assert.assertEquals(-1, access(r, way(tags("highway", "track", "access", "no"))));
	}

	@Test
	public void testPedelecCopiesAgree() {
		String[] highways = {"pedestrian", "footway", "path", "cycleway", "residential", "tertiary", "track", "living_street"};
		String[][] options = {
				{"foot", null, "no", "yes", "designated"},
				{"bicycle", null, "no", "yes", "designated", "dismount"},
				{"moped", null, "no", "yes", "dismount"},
				{"segregated", null, "no", "yes"},
				{"motor_vehicle", null, "no", "agricultural"},
				{"vehicle", null, "no"},
				{"access", null, "no"},
				{"route_bicycle", null, "yes"},
				{"oneway", null, "yes"},
		};
		List<Map<String, String>> combos = combos(highways, options);
		int checked = 0;
		int walking = 0;
		for (String[] params : PEDELEC_PARAM_SETS) {
			GeneralRouter ride = router(SPEED_PEDELEC, params);
			GeneralRouter walk = router(SPEED_PEDELEC, with(params, ALLOW_WALKING));
			for (Map<String, String> tags : combos) {
				RouteDataObject way = way(tags);
				String msg = tags + " " + String.join(",", params);
				int rideAccess = access(ride, way);
				int walkAccess = access(walk, way);
				if (rideAccess == 1) {
					Assert.assertEquals("riding stays allowed: " + msg, 1, walkAccess);
					Assert.assertEquals("speed: " + msg, ride.defineRoutingSpeed(way, true), walk.defineRoutingSpeed(way, true), 0.001);
					Assert.assertEquals("priority: " + msg, ride.defineSpeedPriority(way, true), walk.defineSpeedPriority(way, true), 0.001);
					Assert.assertEquals("oneway: " + msg, ride.isOneWay(way), walk.isOneWay(way));
					Assert.assertEquals("transition: " + msg, 0, walk.getPenaltyTransition(way), 0.001);
					if (ruleSpeed(walk, way) <= PEDELEC_WALKING_KMH + 0.01) {
						// dismount signs: legal, but on foot
						Assert.assertTrue("only dismount signs walk: " + msg, tags.containsValue("dismount"));
					}
				} else if (walkAccess == 1) {
					walking++;
					Assert.assertEquals("walking speed: " + msg, PEDELEC_WALKING_KMH, ruleSpeed(walk, way), 0.01);
					Assert.assertEquals("walking priority: " + msg, PEDELEC_WALKING_PRIORITY, walk.defineSpeedPriority(way, true), 0.001);
					Assert.assertEquals("walking oneway: " + msg, 0, walk.isOneWay(way));
					Assert.assertEquals("walking transition: " + msg, WALKING_TRANSITION, walk.getPenaltyTransition(way), 0.001);
				}
				checked++;
			}
		}
		Assert.assertTrue(walking > 1000);
		Assert.assertTrue(checked > 100000);
	}

	// ---------------------------------------------------------------- bicycle and the profiles that extend it

	@Test
	public void testBicycleWalksWhereRidingIsNotAllowed() {
		for (String profile : BICYCLES) {
			assertBicycleWalk(profile, tags("highway", "pedestrian"));
			assertBicycleWalk(profile, tags("highway", "pedestrian", "oneway", "yes"));
			assertBicycleWalk(profile, tags("highway", "footway"));
			assertBicycleWalk(profile, tags("highway", "footway", "footway", "sidewalk"));
			assertBicycleWalk(profile, tags("highway", "path", "bicycle", "dismount"));
			assertBicycleWalk(profile, tags("highway", "residential", "vehicle", "no"));
			// bicycle=no: closed when riding, on foot with walking
			assertBicycleWalk(profile, tags("highway", "path", "bicycle", "no"));
			Assert.assertEquals(-1, access(router(profile), way(tags("highway", "path", "bicycle", "no"))));
		}
	}

	@Test
	public void testBicycleRidesWhereAllowed() {
		for (String profile : BICYCLES) {
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "pedestrian", "bicycle", "yes"));
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "footway", "bicycle", "designated"));
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "footway", "footway", "crossing"));
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "residential"));
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "cycleway"));
			// 5 km/h in the speed and safety styles: slow riding, not walking
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "footway", "footway", "sidewalk", "bicycle", "yes"), "driving_style_speed");
			assertRide(profile, BICYCLE_WALKING_KMH, tags("highway", "footway", "footway", "sidewalk", "bicycle", "yes"), "driving_style_safety");
		}
	}

	@Test
	public void testBicycleNeverWalks() {
		for (String profile : BICYCLES) {
			GeneralRouter r = router(profile, ALLOW_WALKING);
			Assert.assertEquals(-1, access(r, way(tags("highway", "footway", "foot", "no", "bicycle", "no"))));
			Assert.assertEquals(-1, access(r, way(tags("highway", "footway", "access", "private"))));
			Assert.assertEquals(-1, access(r, way(tags("highway", "footway", "access", "no"))));
			Assert.assertEquals(-1, access(r, way(tags("highway", "motorway", "bicycle", "no"))));
			Assert.assertEquals(-1, access(r, way(tags("highway", "primary", "motorroad", "yes", "bicycle", "no"))));
			Assert.assertEquals(-1, access(r, way(tags("highway", "construction", "bicycle", "no"))));
		}
	}

	@Test
	public void testBicycleCopiesAgree() {
		String[] highways = {"pedestrian", "footway", "path", "cycleway", "residential", "primary", "track", "living_street", "steps"};
		String[][] options = {
				{"foot", null, "no", "yes", "designated"},
				{"bicycle", null, "no", "yes", "designated", "dismount"},
				{"footway", null, "sidewalk", "crossing"},
				{"vehicle", null, "no"},
				{"access", null, "no", "private"},
				{"route_bicycle", null, "yes"},
				{"oneway", null, "yes"},
		};
		List<Map<String, String>> combos = combos(highways, options);
		int checked = 0;
		int walking = 0;
		for (String profile : new String[] {"bicycle", "e_bike"}) {
			for (String[] params : BICYCLE_PARAM_SETS) {
				GeneralRouter ride = router(profile, params);
				GeneralRouter walk = router(profile, with(params, ALLOW_WALKING));
				for (Map<String, String> tags : combos) {
					RouteDataObject way = way(tags);
					String msg = profile + " " + tags + " " + String.join(",", params);
					int rideAccess = access(ride, way);
					int walkAccess = access(walk, way);
					boolean changed = rideAccess != walkAccess || ruleSpeed(ride, way) != ruleSpeed(walk, way);
					if (changed) {
						walking++;
						Assert.assertEquals("walking access: " + msg, 1, walkAccess);
						Assert.assertEquals("walking speed: " + msg, BICYCLE_WALKING_KMH, ruleSpeed(walk, way), 0.01);
						Assert.assertEquals("walking oneway: " + msg, 0, walk.isOneWay(way));
						Assert.assertEquals("walking transition: " + msg, WALKING_TRANSITION, walk.getPenaltyTransition(way), 0.001);
						Assert.assertEquals("priority: " + msg, ride.defineSpeedPriority(way, true), walk.defineSpeedPriority(way, true), 0.001);
					} else if (walkAccess == 1) {
						Assert.assertEquals("oneway: " + msg, ride.isOneWay(way), walk.isOneWay(way));
						Assert.assertEquals("transition: " + msg, ride.getPenaltyTransition(way), walk.getPenaltyTransition(way), 0.001);
						// Only the profile's own walking pace (steps) may look like walking
						if (!"steps".equals(tags.get("highway"))) {
							Assert.assertTrue("riding is not walking: " + msg, ruleSpeed(walk, way) > BICYCLE_WALKING_KMH + 0.5);
						}
					}
					checked++;
				}
			}
		}
		Assert.assertTrue(walking > 1000);
		Assert.assertTrue(checked > 50000);
	}

	// ---------------------------------------------------------------- step off / get back on

	@Test
	public void testMarkWalks() {
		for (String profile : new String[] {SPEED_PEDELEC, "bicycle", "e_bike"}) {
			List<RouteSegmentResult> route = route(
					segment(tags("highway", "residential"), 100),
					segment(tags("highway", "pedestrian"), 40),
					segment(tags("highway", "footway"), 30),
					segment(tags("highway", "residential"), 100),
					// too short to announce
					segment(tags("highway", "footway"), 10),
					segment(tags("highway", "residential"), 100),
					// walk to the destination
					segment(tags("highway", "pedestrian"), 50));
			RouteResultPreparation.markWalkingWithBike(context(profile, ALLOW_WALKING), route);
			int[] expected = {TurnType.WALK_NONE, TurnType.WALK_START, TurnType.WALK_NONE, TurnType.WALK_END,
					TurnType.WALK_NONE, TurnType.WALK_NONE, TurnType.WALK_START};
			Assert.assertArrayEquals(profile, expected, walks(route));

			List<RouteSegmentResult> off = route(segment(tags("highway", "residential"), 100),
					segment(tags("highway", "pedestrian"), 40), segment(tags("highway", "residential"), 100));
			RouteResultPreparation.markWalkingWithBike(context(profile), off);
			Assert.assertArrayEquals(profile, new int[3], walks(off));
		}
	}

	@Test
	public void testStairsAlwaysAnnounced() {
		for (String profile : new String[] {"bicycle", "e_bike", "cargo_bike"}) {
			// Even a few metres of stairs, and also with walking switched off: the bicycle rules still use stairs
			for (String[] params : new String[][] {{ALLOW_WALKING}, {}}) {
				String msg = profile + " " + String.join(",", params);
				List<RouteSegmentResult> route = route(segment(tags("highway", "residential"), 100),
						segment(tags("highway", "steps"), 4), segment(tags("highway", "residential"), 100));
				RouteResultPreparation.markWalkingWithBike(context(profile, params), route);
				Assert.assertArrayEquals(msg, new int[] {TurnType.WALK_NONE, TurnType.WALK_START_STAIRS, TurnType.WALK_END}, walks(route));
				Assert.assertTrue(msg, route.get(1).getTurnType().isWalkStart());
				Assert.assertFalse(msg, route.get(1).getTurnType().isSkipToSpeak());
			}

			// Stairs a few metres after stepping off: the stairs phrase, for the whole short walk
			List<RouteSegmentResult> route = route(segment(tags("highway", "residential"), 100),
					segment(tags("highway", "footway"), 5), segment(tags("highway", "steps"), 6),
					segment(tags("highway", "residential"), 100));
			RouteResultPreparation.markWalkingWithBike(context(profile, ALLOW_WALKING), route);
			Assert.assertArrayEquals(profile, new int[] {TurnType.WALK_NONE, TurnType.WALK_START_STAIRS,
					TurnType.WALK_NONE, TurnType.WALK_END}, walks(route));

			// Stairs far into a walk: the rider is already on foot there
			route = route(segment(tags("highway", "residential"), 100), segment(tags("highway", "pedestrian"), 60),
					segment(tags("highway", "steps"), 6), segment(tags("highway", "residential"), 100));
			RouteResultPreparation.markWalkingWithBike(context(profile, ALLOW_WALKING), route);
			Assert.assertArrayEquals(profile, new int[] {TurnType.WALK_NONE, TurnType.WALK_START,
					TurnType.WALK_NONE, TurnType.WALK_END}, walks(route));

			// Walking off: footways are ridden as in routing.xml, only the stairs are announced
			route = route(segment(tags("highway", "residential"), 100), segment(tags("highway", "footway"), 60),
					segment(tags("highway", "steps"), 6), segment(tags("highway", "residential"), 100));
			RouteResultPreparation.markWalkingWithBike(context(profile), route);
			Assert.assertArrayEquals(profile, new int[] {TurnType.WALK_NONE, TurnType.WALK_NONE,
					TurnType.WALK_START_STAIRS, TurnType.WALK_END}, walks(route));
		}
	}

	// ---------------------------------------------------------------- helpers

	private static void assertPedelecWalk(Map<String, String> tags, String... params) {
		RouteDataObject way = way(tags);
		GeneralRouter r = router(SPEED_PEDELEC, with(params, ALLOW_WALKING));
		Assert.assertEquals(tags.toString(), 1, access(r, way));
		Assert.assertEquals(tags.toString(), PEDELEC_WALKING_KMH, ruleSpeed(r, way), 0.01);
		Assert.assertEquals(tags.toString(), 0, r.isOneWay(way));
		Assert.assertEquals(tags.toString(), -1, access(router(SPEED_PEDELEC, params), way));
	}

	private static void assertBicycleWalk(String profile, Map<String, String> tags) {
		RouteDataObject way = way(tags);
		GeneralRouter r = router(profile, ALLOW_WALKING);
		String msg = profile + " " + tags;
		Assert.assertEquals(msg, 1, access(r, way));
		Assert.assertEquals(msg, BICYCLE_WALKING_KMH, ruleSpeed(r, way), 0.01);
		Assert.assertEquals(msg, 0, r.isOneWay(way));
		Assert.assertEquals(msg, WALKING_TRANSITION, r.getPenaltyTransition(way), 0.001);
	}

	private static void assertRide(String profile, double walkingKmh, Map<String, String> tags, String... params) {
		RouteDataObject way = way(tags);
		GeneralRouter r = router(profile, with(params, ALLOW_WALKING));
		String msg = profile + " " + tags + " " + String.join(",", params);
		Assert.assertEquals(msg, 1, access(r, way));
		Assert.assertTrue(msg, ruleSpeed(r, way) > walkingKmh + 0.5);
	}

	private static RouteSegmentResult segment(Map<String, String> tags, float distance) {
		RouteSegmentResult s = new RouteSegmentResult(way(tags), 0, 1);
		s.setDistance(distance);
		return s;
	}

	private static List<RouteSegmentResult> route(RouteSegmentResult... segments) {
		return new ArrayList<>(Arrays.asList(segments));
	}

	private static int[] walks(List<RouteSegmentResult> route) {
		int[] res = new int[route.size()];
		for (int i = 0; i < res.length; i++) {
			TurnType t = route.get(i).getTurnType();
			res[i] = t == null ? TurnType.WALK_NONE : t.getWalkWithBike();
		}
		return res;
	}

	// km/h as the rules give it, before minSpeed: what markWalkingWithBike compares
	private static double ruleSpeed(GeneralRouter router, RouteDataObject way) {
		return router.getObjContext(RouteDataObjectAttribute.ROAD_SPEED).evaluateFloat(way, Float.MAX_VALUE) * 3.6;
	}

	private static double walkingSpeed(String profile) {
		return Float.parseFloat(router(profile).getAttribute(RouteResultPreparation.WALKING_SPEED));
	}

	private static List<Map<String, String>> combos(String[] highways, String[][] options) {
		List<Map<String, String>> combos = new ArrayList<>();
		for (String hw : highways) {
			Map<String, String> base = new LinkedHashMap<>();
			base.put("highway", hw);
			expand(base, options, 0, combos);
		}
		return combos;
	}

	private static void expand(Map<String, String> base, String[][] options, int i, List<Map<String, String>> out) {
		if (i == options.length) {
			out.add(base);
			return;
		}
		for (int v = 1; v < options[i].length; v++) {
			Map<String, String> next = new LinkedHashMap<>(base);
			if (options[i][v] != null) {
				next.put(options[i][0], options[i][v]);
			}
			expand(next, options, i + 1, out);
		}
	}

	private static int access(GeneralRouter router, RouteDataObject way) {
		return router.getObjContext(RouteDataObjectAttribute.ACCESS).evaluateInt(way, 0);
	}

	private static String[] with(String[] values, String extra) {
		String[] res = Arrays.copyOf(values, values.length + 1);
		res[values.length] = extra;
		return res;
	}

	private static RoutingConfiguration config(String profile, String... params) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String param : params) {
			values.put(param, "true");
		}
		return builder.build(profile, new RoutingMemoryLimits(30, 256), values);
	}

	private static GeneralRouter router(String profile, String... params) {
		return config(profile, params).router;
	}

	private static RoutingContext context(String profile, String... params) {
		return new RoutePlannerFrontEnd().buildRoutingContext(config(profile, params), null, new BinaryMapIndexReader[0]);
	}
}
