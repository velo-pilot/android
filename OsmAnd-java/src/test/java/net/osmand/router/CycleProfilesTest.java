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
 * The cycle node network option (router_profiles/bicycle.xml, speed_pedelec.xml) and the e-bike profile
 * (router_profiles/e_bike.xml), which extends the bicycle profile of routing.xml.
 */
public class CycleProfilesTest {

	private static final String[] NODE_NETWORK_PROFILES = {"bicycle", "e_bike", "speed_pedelec"};
	private static final String PREFER = "node_network_prefer";
	private static final String STRICT = "node_network_strict";
	private static final String LEISURE = "driving_style_leisure";

	private static RoutingConfiguration.Builder builder;

	@BeforeClass
	public static void setUp() {
		builder = RoutingConfiguration.parseDefault();
	}

	@Test
	public void testEBikeExtendsBicycle() {
		GeneralRouter bicycle = builder.getRouter("bicycle");
		GeneralRouter eBike = builder.getRouter("e_bike");
		Assert.assertNotNull(eBike);
		Assert.assertEquals(GeneralRouterProfile.BICYCLE, eBike.getProfile());
		Assert.assertEquals("e_bike", eBike.getProfileName());
		Assert.assertEquals(bicycle.getParameters().keySet(), eBike.getParameters().keySet());
		Assert.assertEquals(bicycle.getMaxSpeed(), eBike.getMaxSpeed(), 0.01);
		Assert.assertEquals(20, eBike.getDefaultSpeed() * 3.6, 0.01);
		Assert.assertEquals("car", builder.getDefaultRouter());

		Map<String, String> footway = tags("highway", "footway");
		Assert.assertEquals(access("bicycle", footway), access("e_bike", footway));
		// the e-bike rule comes before the bicycle one
		Assert.assertEquals(0.3, priority("bicycle", tags("highway", "steps")), 0.001);
		Assert.assertEquals(0.1, priority("e_bike", tags("highway", "steps")), 0.001);
		Assert.assertEquals(priority("bicycle", tags("highway", "cycleway")), priority("e_bike", tags("highway", "cycleway")), 0.001);
	}

	@Test
	public void testEBikeClimbs() {
		Assert.assertEquals(16, climbPenalty("bicycle", 10), 0.001);
		Assert.assertEquals(6, climbPenalty("e_bike", 10), 0.001);
		Assert.assertEquals(2, climbPenalty("e_bike", 2), 0.001);
		Assert.assertEquals(climbPenalty("bicycle", 10, "relief_smoothness_factor_more_plains"),
				climbPenalty("e_bike", 10, "relief_smoothness_factor_more_plains"), 0.001);
	}

	@Test
	public void testNodeNetworkParameters() {
		for (String profile : NODE_NETWORK_PROFILES) {
			Map<String, GeneralRouter.RoutingParameter> parameters = builder.getRouter(profile).getParameters();
			for (String id : new String[] {"node_network_off", PREFER, STRICT}) {
				Assert.assertEquals(profile + " " + id, "node_network", parameters.get(id).getGroup());
			}
			Assert.assertTrue(parameters.get("node_network_off").getDefaultBoolean());
		}
	}

	@Test
	public void testNodeNetworkPriority() {
		Map<String, String> offNetwork = tags("highway", "residential");
		Map<String, String> nodeNetwork = tags("highway", "residential", "route_bicycle", "");
		Map<String, String> longDistanceRoute = tags("highway", "residential", "route_bicycle", "", "network", "ncn");
		for (String profile : NODE_NETWORK_PROFILES) {
			float off = priority(profile, offNetwork);
			float prefer = priority(profile, offNetwork, PREFER);
			float strict = priority(profile, offNetwork, STRICT);
			Assert.assertTrue(profile, off > prefer && prefer > strict);
			Assert.assertEquals(off, priority(profile, offNetwork, "node_network_off"), 0.001);
			for (String param : new String[] {PREFER, STRICT}) {
				Assert.assertEquals(profile, priority(profile, nodeNetwork), priority(profile, nodeNetwork, param), 0.001);
				Assert.assertEquals(profile, priority(profile, longDistanceRoute), priority(profile, longDistanceRoute, param), 0.001);
			}
			// off the network the profile keeps its order
			Assert.assertTrue(profile, priority(profile, tags("highway", "cycleway"), STRICT)
					> priority(profile, tags("highway", "primary"), STRICT));
			Assert.assertTrue(profile, priority(profile, tags("highway", "residential", "surface", "sand"), PREFER)
					< priority(profile, offNetwork, PREFER));
		}
	}

	@Test
	public void testLeisure() {
		for (String profile : NODE_NETWORK_PROFILES) {
			Assert.assertEquals(profile, "driving_style",
					builder.getRouter(profile).getParameters().get(LEISURE).getGroup());
			float primary = priority(profile, tags("highway", "primary"), LEISURE);
			float cycleway = priority(profile, tags("highway", "cycleway"), LEISURE);
			Assert.assertTrue(profile, primary < priority(profile, tags("highway", "primary")));
			Assert.assertTrue(profile, cycleway > 2.5 * primary);
			Assert.assertTrue(profile, priority(profile, tags("highway", "residential", "route_bicycle", ""), LEISURE)
					> priority(profile, tags("highway", "residential"), LEISURE));
			// a cycle route along a main road is still a main road
			Assert.assertEquals(profile, primary,
					priority(profile, tags("highway", "primary", "route_bicycle", ""), LEISURE), 0.001);
			Assert.assertFalse(profile, router(profile, LEISURE).isHHRoutingAllowed());
		}
		// off the node network, the node network table wins
		Assert.assertEquals(priority("bicycle", tags("highway", "cycleway"), STRICT),
				priority("bicycle", tags("highway", "cycleway"), STRICT, LEISURE), 0.001);
	}

	@Test
	public void testNodeNetworkKeepsAccess() {
		Map<String, String> footway = tags("highway", "footway", "route_bicycle", "");
		Assert.assertEquals(-1, access("speed_pedelec", footway, STRICT));
		Assert.assertEquals(1, access("bicycle", footway, STRICT));
	}

	@Test
	public void testHHRouting() {
		Assert.assertTrue(router("bicycle").isHHRoutingAllowed());
		Assert.assertTrue(router("bicycle", "node_network_off").isHHRoutingAllowed());
		Assert.assertTrue(router("bicycle", "driving_style_safety").isHHRoutingAllowed());
		Assert.assertFalse(router("bicycle", PREFER).isHHRoutingAllowed());
		Assert.assertFalse(router("bicycle", STRICT).isHHRoutingAllowed());
		Assert.assertFalse(router("e_bike").isHHRoutingAllowed());
		Map<String, String> disabled = new LinkedHashMap<>();
		disabled.put(PREFER, "false");
		Assert.assertTrue(builder.build("bicycle", new RoutingMemoryLimits(30, 256), disabled).router.isHHRoutingAllowed());
	}

	private static float priority(String profile, Map<String, String> tags, String... params) {
		return router(profile, params).defineSpeedPriority(way(tags), true);
	}

	private static int access(String profile, Map<String, String> tags, String... params) {
		return router(profile, params).getObjContext(RouteDataObjectAttribute.ACCESS).evaluateInt(way(tags), 0);
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

	private static GeneralRouter router(String profile, String... params) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String param : params) {
			values.put(param, "true");
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
		way.pointsX = new int[] {0, 100};
		way.pointsY = new int[] {0, 100};
		return way;
	}
}
