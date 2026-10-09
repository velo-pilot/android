package net.osmand.render;

import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class SpeedPedelecRenderStyleTest {

	private static final String[] CYCLE_HIGHWAY = {"route_type", "cycling", "route_activity_type", "road_cycling", "network", "ncn"};

	private static RenderingRulesStorage defaultStyle;
	private static RenderingRulesStorage speedPedelec;

	@BeforeClass
	public static void setUp() throws Exception {
		defaultStyle = load("default");
		speedPedelec = load("speed_pedelec");
	}

	@Test
	public void testStyleDependsOnDefault() {
		Assert.assertEquals("default", speedPedelec.getDependsName());
		Assert.assertNotNull(speedPedelec.PROPS.getCustomRule("hideCycleHighways"));
	}

	@Test
	public void testMotorwaysAreMuted() {
		Assert.assertEquals("#d6d2cd", color(request(speedPedelec, false), 15, speedPedelec.PROPS.R_COLOR, "highway", "motorway"));
		Assert.assertEquals("#4a5256", color(request(speedPedelec, true), 15, speedPedelec.PROPS.R_COLOR, "highway", "motorway"));
		Assert.assertEquals("#dcd8d3", color(request(speedPedelec, false), 13, speedPedelec.PROPS.R_COLOR, "highway", "trunk"));
		Assert.assertEquals(color(request(defaultStyle, false), 14, defaultStyle.PROPS.R_COLOR, "highway", "primary"),
				color(request(speedPedelec, false), 14, speedPedelec.PROPS.R_COLOR, "highway", "primary"));
	}

	@Test
	public void testRailwaysAreProminent() {
		Assert.assertTrue(order(request(defaultStyle, false), 9, "railway", "rail") < 0);
		Assert.assertTrue(order(request(speedPedelec, false), 9, "railway", "rail") > 0);
		Assert.assertTrue(order(request(speedPedelec, false), 10, "railway", "rail", "tunnel", "yes") < 0);
		Assert.assertEquals("#4d4d4d", color(request(speedPedelec, false), 12, speedPedelec.PROPS.R_COLOR, "railway", "rail"));
		Assert.assertEquals(color(request(defaultStyle, false), 15, defaultStyle.PROPS.R_COLOR, "railway", "abandoned"),
				color(request(speedPedelec, false), 15, speedPedelec.PROPS.R_COLOR, "railway", "abandoned"));
	}

	@Test
	public void testCyclePaths() {
		RenderingRuleSearchRequest req = request(speedPedelec, false);
		Assert.assertTrue(order(request(defaultStyle, false), 12, "highway", "cycleway") < 0);
		Assert.assertEquals(177, order(req, 12, "highway", "cycleway"));
		Assert.assertEquals("#0a64c8", color(req, 12, speedPedelec.PROPS.R_COLOR, "highway", "cycleway"));
		Assert.assertEquals("#0a64c8", color(req, 15, speedPedelec.PROPS.R_COLOR, "highway", "path", "bicycle", "designated"));
		Assert.assertEquals("#000000", color(req, 16, speedPedelec.PROPS.R_COLOR__1, "highway", "cycleway", "bridge", "yes"));
		// With road surface display on, the default rule (and its surface overlay) is used
		Assert.assertNotNull(color(request(speedPedelec, false, "showSurfaces"), 16, speedPedelec.PROPS.R_COLOR_5, "highway", "cycleway"));
	}

	@Test
	public void testCycleHighways() {
		RenderingRuleSearchRequest req = request(speedPedelec, false);
		Assert.assertEquals("#0a7c86", color(req, 9, speedPedelec.PROPS.R_COLOR, CYCLE_HIGHWAY));
		Assert.assertTrue(order(req, 13, CYCLE_HIGHWAY) > 0);
		Assert.assertNotEquals("#0a7c86", color(request(speedPedelec, false, "hideCycleHighways"), 13, speedPedelec.PROPS.R_COLOR, CYCLE_HIGHWAY));
		Assert.assertNotEquals("#0a7c86", color(req, 13, speedPedelec.PROPS.R_COLOR,
				"route_type", "cycling", "route_activity_type", "road_cycling", "network", "rcn", "node_network", "rcn"));

		prepare(req, 13, CYCLE_HIGHWAY);
		req.setIntFilter(speedPedelec.PROPS.R_TEXT_LENGTH, 2);
		req.setStringFilter(speedPedelec.PROPS.R_NAME_TAG, "ref");
		Assert.assertTrue(req.search(RenderingRulesStorage.TEXT_RULES));
		Assert.assertEquals("blue_rounded_up_triangle_2_road_shield", req.getStringPropertyValue(speedPedelec.PROPS.R_TEXT_SHIELD));
	}

	@Test
	public void testRecreationalRoutesAreNotCycleHighways() {
		// Flemish icoonroutes are national routes too, but carry a route colour
		String[] icoonroute = {"route_type", "cycling", "route_activity_type", "road_cycling", "network", "ncn", "track_color", "red"};
		RenderingRuleSearchRequest req = request(speedPedelec, false);
		Assert.assertNotEquals("#0a7c86", color(req, 9, speedPedelec.PROPS.R_COLOR, icoonroute));
		Assert.assertEquals("#80e40613", color(req, 13, speedPedelec.PROPS.R_COLOR, icoonroute));

		prepare(req, 15, icoonroute);
		req.setIntFilter(speedPedelec.PROPS.R_TEXT_LENGTH, 5);
		req.setStringFilter(speedPedelec.PROPS.R_NAME_TAG, "ref");
		Assert.assertTrue(req.search(RenderingRulesStorage.TEXT_RULES));
		Assert.assertFalse(req.isSpecified(speedPedelec.PROPS.R_TEXT_SHIELD));
	}

	// Loads a style like RendererRegistry: constants of the requested style win, then the routes addon
	private static RenderingRulesStorage load(String name) throws Exception {
		Map<String, String> constants = new LinkedHashMap<>();
		RenderingRulesStorage main = load(null, name + ".render.xml", constants);
		load(main, "routes.addon.render.xml", constants);
		return main;
	}

	private static RenderingRulesStorage load(RenderingRulesStorage main, String resource, Map<String, String> constants) throws Exception {
		try (InputStream is = RenderingRulesStorage.class.getResourceAsStream(resource)) {
			XmlPullParser parser = PlatformUtil.newXMLPullParser();
			parser.setInput(is, "UTF-8");
			int tok;
			while ((tok = parser.next()) != XmlPullParser.END_DOCUMENT) {
				if (tok == XmlPullParser.START_TAG && parser.getName().equals("renderingConstant")) {
					constants.putIfAbsent(parser.getAttributeValue("", "name"), parser.getAttributeValue("", "value"));
				}
			}
		}
		boolean addon = main != null;
		if (main == null) {
			main = new RenderingRulesStorage(resource.substring(0, resource.indexOf('.')), constants);
		}
		try (InputStream is = RenderingRulesStorage.class.getResourceAsStream(resource)) {
			main.parseRulesFromXmlInputStream(is, (nm, ref) -> {
				try {
					return load(null, nm + ".render.xml", constants);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}, addon);
		}
		return main;
	}

	private static RenderingRuleSearchRequest request(RenderingRulesStorage storage, boolean nightMode, String... enabledProps) {
		RenderingRuleSearchRequest req = new RenderingRuleSearchRequest(storage);
		req.setBooleanFilter(req.ALL.R_NIGHT_MODE, nightMode);
		Set<String> enabled = new HashSet<>(Arrays.asList(enabledProps));
		for (RenderingRuleProperty p : storage.PROPS.getCustomRules()) {
			if (p.isBoolean()) {
				req.setBooleanFilter(p, enabled.contains(p.getAttrName()));
			} else if (p.isString()) {
				String value = "";
				if (p.getAttrName().equals("baseAppMode")) {
					value = "bicycle";
				} else if (p.getAttrName().equals("appMode")) {
					value = "speed_pedelec";
				}
				req.setStringFilter(p, value);
			}
		}
		Map<String, Boolean> parentStates = new HashMap<>();
		for (Map.Entry<String, RenderingClass> e : storage.getRenderingClasses().entrySet()) {
			String parent = e.getValue().getParentName();
			boolean on = e.getValue().isEnabledByDefault()
					&& (parent == null || !parentStates.containsKey(parent) || parentStates.get(parent));
			req.setClassProperty(e.getKey(), String.valueOf(on));
			parentStates.put(e.getKey(), on);
		}
		req.saveState();
		return req;
	}

	private static void prepare(RenderingRuleSearchRequest req, int zoom, String... tags) {
		MapIndex mapIndex = new MapIndex();
		int[] additional = new int[tags.length / 2 - 1];
		for (int i = 0; i < tags.length / 2; i++) {
			mapIndex.initMapEncodingRule(0, i + 1, tags[2 * i], tags[2 * i + 1]);
			if (i > 0) {
				additional[i - 1] = i + 1;
			}
		}
		BinaryMapDataObject obj = new BinaryMapDataObject(1, new int[] {0, 0, 100, 100}, null, 2, false,
				new int[] {1}, additional, 0, 0);
		obj.setMapIndex(mapIndex);
		req.clearState();
		req.setInitialTagValueZoom(tags[0], tags[1], zoom, obj);
	}

	private static int order(RenderingRuleSearchRequest req, int zoom, String... tags) {
		prepare(req, zoom, tags);
		RenderingRuleStorageProperties props = req.getStorage().PROPS;
		req.setIntFilter(props.R_LAYER, 0);
		req.setBooleanFilter(props.R_AREA, false);
		req.setBooleanFilter(props.R_POINT, false);
		req.setBooleanFilter(props.R_CYCLE, false);
		return req.search(RenderingRulesStorage.ORDER_RULES) ? req.getIntPropertyValue(props.R_ORDER) : -1;
	}

	private static String color(RenderingRuleSearchRequest req, int zoom, RenderingRuleProperty p, String... tags) {
		prepare(req, zoom, tags);
		if (!req.search(RenderingRulesStorage.LINE_RULES) || !req.isSpecified(p)) {
			return null;
		}
		return req.getColorStringPropertyValue(p);
	}
}
