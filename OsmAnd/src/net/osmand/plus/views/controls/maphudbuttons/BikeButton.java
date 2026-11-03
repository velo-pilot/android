package net.osmand.plus.views.controls.maphudbuttons;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.bikes.BikeDialogs;
import net.osmand.plus.bikes.BikeHelper;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.views.mapwidgets.configure.buttons.BikeButtonState;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;
import net.osmand.shared.vehicle.battery.Bike;

import kotlin.Unit;

/**
 * Switches between the user's bikes: the profile of the bike's type with the bike's riding mode. A long press
 * opens the list of bikes.
 */
public class BikeButton extends MapButton {

	private final BikeButtonState buttonState;

	public BikeButton(@NonNull Context context) {
		this(context, null);
	}

	public BikeButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public BikeButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		buttonState = app.getMapButtonsHelper().getBikeButtonState();

		setOnClickListener(v -> {
			if (AndroidUtils.isActivityNotDestroyed(mapActivity)) {
				BikeDialogs.showBikePicker(mapActivity, nightMode, bike -> {
					onBikeSelected(bike);
					return Unit.INSTANCE;
				});
			}
		});
		setOnLongClickListener(v -> {
			if (AndroidUtils.isActivityNotDestroyed(mapActivity)) {
				BikeDialogs.showGarage(mapActivity, nightMode, this::update);
			}
			return true;
		});
	}

	private void onBikeSelected(@NonNull Bike bike) {
		new BikeHelper(app).selectBike(bike);
		mapActivity.updateApplicationModeSettings();
		// two ways of use of a bike can share the profile but not the map style
		mapActivity.updateMapSettings(true);
		mapActivity.refreshMap();
		update();
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return buttonState;
	}

	@Override
	protected void updateColors(boolean nightMode) {
		BikeHelper helper = new BikeHelper(app);
		setIconColor(helper.getColor(helper.getCurrentBike(settings.getApplicationMode()), nightMode));
		setBackgroundColors(ColorUtilities.getMapButtonBackgroundColor(getContext(), nightMode),
				ColorUtilities.getMapButtonBackgroundPressedColor(getContext(), nightMode));
	}

	@Override
	protected boolean shouldShow() {
		return !routeDialogOpened && visibilityHelper.shouldShowTopButtons();
	}
}
