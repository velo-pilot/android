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

public class SpeedPedelecProfileTest {

	private static final String PROFILE = "speed_pedelec";
	private static final String MOPED_RULES = "moped_rules";

	private static RoutingConfiguration.Builder builder;

	@BeforeClass
	public static void setUp() {
		builder = RoutingConfiguration.parseDefault();
	}

	@Test
	public void testProfileIsBundledWithDefaultConfig() {
		GeneralRouter router = builder.getRouter(PROFILE);
		Assert.assertNotNull(router);
		Assert.assertNull(router.getFilename());
		Assert.assertEquals(GeneralRouterProfile.MOPED, router.getProfile());
		Assert.assertEquals("car", builder.getDefaultRouter());
		Assert.assertNotNull(builder.getRouter("car"));
	}

	@Test
	public void testNeverAllowed() {
		assertAccess(-1, tags("highway", "motorway"));
		assertAccess(-1, tags("highway", "trunk"));
		assertAccess(-1, tags("highway", "primary", "motorroad", "yes"));
		assertAccess(-1, tags("highway", "steps"));
		assertAccess(-1, tags("highway", "footway"));
		assertAccess(-1, tags("highway", "pedestrian", "bicycle", "yes"));
		assertAccess(1, tags("highway", "pedestrian"), "allow_pedestrian_zones");
		assertAccess(1, tags("highway", "trunk", "moped", "yes"));
	}

	@Test
	public void testBelgianCyclePaths() {
		assertAccess(1, tags("highway", "cycleway"));
		// D7 + M7 or D7 + M16
		assertAccess(1, tags("highway", "cycleway", "moped", "no"));
		assertAccess(-1, tags("highway", "cycleway", "moped", "no"), "avoid_moped_no_cycleways");
		// D9 and D10
		assertAccess(1, tags("highway", "footway", "bicycle", "designated", "foot", "designated", "segregated", "yes"));
		assertAccess(-1, tags("highway", "cycleway", "foot", "designated", "segregated", "no"));
		// Cycle path compulsory above 50 km/h
		assertAccess(1, tags("highway", "primary", "maxspeed", "50", "bicycle", "use_sidepath"));
		assertAccess(-1, tags("highway", "primary", "maxspeed", "70", "bicycle", "use_sidepath"));
		assertAccess(-1, tags("highway", "secondary", "maxspeed", "70", "cycleway", "separate"));
		// C9 or moped=use_sidepath
		assertAccess(-1, tags("highway", "residential", "moped", "no"));
	}

	@Test
	public void testBelgianRoadsClosedToVehicles() {
		Map<String, String> onRoute = tags("highway", "path", "motor_vehicle", "no", "bicycle", "designated", "route_bicycle", "yes");
		Map<String, String> offRoute = tags("highway", "path", "motor_vehicle", "no", "bicycle", "designated");
		assertAccess(1, onRoute);
		assertAccess(-1, offRoute);
		assertAccess(1, offRoute, "allow_closed_to_vehicles");
		assertAccess(-1, onRoute, "avoid_closed_to_vehicles");
		assertAccess(-1, tags("highway", "track", "motor_vehicle", "agricultural"), "allow_closed_to_vehicles");
		assertAccess(1, tags("highway", "cycleway", "motor_vehicle", "no"));
		assertAccess(-1, tags("highway", "pedestrian", "motor_vehicle", "no", "bicycle", "yes", "route_bicycle", "yes"));
	}

	@Test
	public void testMopedRules() {
		assertAccess(-1, tags("highway", "cycleway"), MOPED_RULES);
		assertAccess(1, tags("highway", "cycleway", "moped", "designated"), MOPED_RULES);
		assertAccess(-1, tags("highway", "primary", "moped", "no"), MOPED_RULES);
		assertAccess(-1, tags("highway", "track", "motor_vehicle", "no", "bicycle", "yes", "route_bicycle", "yes"), MOPED_RULES);
		assertAccess(1, tags("highway", "residential", "bicycle", "no"), MOPED_RULES);
		assertAccess(1, tags("highway", "primary", "maxspeed", "70", "bicycle", "use_sidepath"), MOPED_RULES);
	}

	@Test
	public void testOneway() {
		Map<String, String> contraflow = tags("highway", "residential", "oneway", "yes", "oneway:bicycle", "no");
		Assert.assertEquals(1, router().isOneWay(way(contraflow)));
		Assert.assertEquals(0, router("allow_bicycle_contraflow").isOneWay(way(contraflow)));
	}

	@Test
	public void testSpeed() {
		assertSpeed(42, tags("highway", "primary", "maxspeed", "50"));
		assertSpeed(30, tags("highway", "residential", "maxspeed", "30"));
		assertSpeed(38, tags("highway", "cycleway"));
		assertSpeed(38, tags("highway", "secondary", "maxspeed", "70", "cycleway", "track"));
		assertSpeed(30, tags("highway", "cycleway", "maxspeed:moped", "30"));
		assertSpeed(35, tags("man_made", "pier"));
		Assert.assertEquals(45, router().getMaxSpeed() * 3.6, 0.01);
	}

	private static void assertAccess(int expected, Map<String, String> tags, String... params) {
		RouteDataObject way = way(tags);
		int access = router(params).getObjContext(RouteDataObjectAttribute.ACCESS).evaluateInt(way, 0);
		Assert.assertEquals(tags + " " + String.join(",", params), expected, access);
	}

	private static void assertSpeed(double expectedKmh, Map<String, String> tags) {
		Assert.assertEquals(tags.toString(), expectedKmh, router().defineRoutingSpeed(way(tags), true) * 3.6, 0.05);
	}

	private static GeneralRouter router(String... params) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String param : params) {
			values.put(param, "true");
		}
		return builder.build(PROFILE, new RoutingMemoryLimits(30, 256), values).router;
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
		way.pointsX = new int[] {0, 100};
		way.pointsY = new int[] {0, 100};
		return way;
	}
}
