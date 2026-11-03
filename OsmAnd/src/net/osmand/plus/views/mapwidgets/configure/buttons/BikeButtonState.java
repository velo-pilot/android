package net.osmand.plus.views.mapwidgets.configure.buttons;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.HUD_BTN_ID_SCHEME;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.SMALL_SIZE_DP;
import static net.osmand.shared.grid.ButtonPositionSize.POS_LEFT;
import static net.osmand.shared.grid.ButtonPositionSize.POS_TOP;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.bikes.BikeHelper;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.shared.grid.ButtonPositionSize;

/**
 * Button in the top left corner that switches between the user's bikes (cycling editions only). It shows the
 * symbol and colour of the current bike; without bikes it adds one.
 */
public class BikeButtonState extends MapButtonState {

	public static final String BIKE_HUD_ID = HUD_BTN_ID_SCHEME + "bike";

	private final CommonPreference<Boolean> visibilityPref;

	public BikeButtonState(@NonNull OsmandApplication app) {
		super(app, BIKE_HUD_ID);
		this.visibilityPref = addPreference(settings.registerBooleanPreference(id + "_state", true)).makeProfile();
	}

	@NonNull
	@Override
	public String getName() {
		return app.getString(R.string.bike_button);
	}

	@NonNull
	@Override
	public String getDescription() {
		return app.getString(R.string.bike_button_descr);
	}

	@Override
	public boolean isEnabled() {
		// shown without bikes too: it is the way to add them
		return BuildConfig.RIDING_QUESTIONS && visibilityPref.get();
	}

	@NonNull
	@Override
	public CommonPreference<Boolean> getVisibilityPref() {
		return visibilityPref;
	}

	@Override
	public int getDefaultLayoutId() {
		return R.layout.map_bike_button;
	}

	@Override
	public int getDefaultSize() {
		return SMALL_SIZE_DP;
	}

	@NonNull
	@Override
	public String getDefaultIconName(@Nullable Boolean nightMode) {
		BikeHelper helper = new BikeHelper(app);
		return helper.getIconName(helper.getCurrentBike(settings.getApplicationMode()));
	}

	@Override
	protected void updatePosition(@NonNull ButtonPositionSize position) {
		super.updatePosition(position);
		if (!portrait) {
			position.setMoveHorizontal();
		}
	}

	@NonNull
	@Override
	protected ButtonPositionSize setupButtonPosition(@NonNull ButtonPositionSize position) {
		return setupButtonPosition(position, POS_LEFT, POS_TOP, true, false);
	}
}
