/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.conversation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.provider.Settings;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.waz.model.AccentColor;
import com.waz.api.MessageContent;
import com.waz.model.ConversationData;
import com.waz.permissions.PermissionsService;
import com.waz.service.ZMessaging;
import com.waz.service.tracking.ContributionEvent;
import com.wire.signals.EventContext;
import com.waz.zclient.BaseActivity;
import com.waz.zclient.BuildConfig;
import com.waz.zclient.OnBackPressedListener;
import com.waz.zclient.R;
import com.waz.zclient.common.controllers.global.AccentColorCallback;
import com.waz.zclient.common.controllers.global.AccentColorController;
import com.waz.zclient.controllers.userpreferences.IUserPreferencesController;
import com.waz.zclient.conversation.ConversationController;
import com.waz.zclient.core.logging.Logger;
import com.waz.zclient.pages.BaseFragment;
import com.waz.zclient.ui.text.GlyphTextView;
import com.waz.zclient.ui.views.TouchRegisteringFrameLayout;
import com.waz.zclient.utils.Callback;
import com.waz.zclient.utils.StringUtils;
import com.waz.zclient.utils.ViewUtils;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.util.List;
import java.util.Locale;

import scala.Option;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

@SuppressLint("All")
public class LocationFragment extends BaseFragment<LocationFragment.Container> implements LocationListener,
                                                                                          TouchRegisteringFrameLayout.TouchCallback,
                                                                                          OnBackPressedListener,
                                                                                          View.OnClickListener {

    private static final float DEFAULT_MAP_ZOOM_LEVEL = 15F;
    private static final float DEFAULT_MIMIMUM_CAMERA_MOVEMENT = 2F;
    // private static final int LOCATION_REQUEST_TIMEOUT_MS = 1500;
    public static final String TAG = "LocationFragment";

    private Toolbar toolbar;
    private MapView map;
    private View selectedLocationBackground;
    private GlyphTextView selectedLocationPin;
    private LinearLayout selectedLocationDetails;
    private TextView selectedLocationAddress;
    private TouchRegisteringFrameLayout touchRegisteringFrameLayout;
    private TextView requestCurrentLocationButton;
    private TextView sendCurrentLocationButton;
    private TextView toolbarTitle;
    private Bitmap marker;

    private LocationManager locationManager;
    private Location currentLocation;

    private String currentLocationCountryName;
    private String currentLocationLocality;
    private String currentLocationSubLocality;
    private String currentLocationFirstAddressLine;
    private String currentLocationName;

    private boolean animateToCurrentLocation;
    private boolean zoom;
    private boolean animating;
    private boolean checkIfLocationServicesEnabled;
    private Handler mainHandler;
    // private Handler backgroundHandler;
    private HandlerThread handlerThread;
    private Geocoder geocoder;

    private int accentColor;

    /*
    private final Runnable updateCurrentLocationBubbleRunnable = new Runnable() {
        @Override
        public void run() {
            if (getActivity() == null || getContainer() == null) {
                return;
            }
            updateCurrentLocationName((int) map.getZoomLevelDouble());
            setTextAddressBubble(currentLocationName);
        }
    };
    */

    /*
    private final Runnable retrieveCurrentLocationNameRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                IGeoPoint center = map.getMapCenter();
                final List<Address> addresses = geocoder.getFromLocation(center.getLatitude(),
                                                                         center.getLongitude(),
                                                                         1);
                if (addresses != null && addresses.size() > 0) {
                    Address adr = addresses.get(0);
                    if (adr.getMaxAddressLineIndex() >= 0) {
                        currentLocationFirstAddressLine = adr.getAddressLine(0);
                    } else {
                        currentLocationFirstAddressLine = "";
                    }
                    currentLocationCountryName = adr.getCountryName();
                    currentLocationLocality =  adr.getLocality();
                    currentLocationSubLocality = adr.getSubLocality();
                } else {
                    currentLocationFirstAddressLine = "";
                    currentLocationCountryName = "";
                    currentLocationLocality = "";
                    currentLocationSubLocality = "";
                }

            } catch (Exception e) {
                currentLocationFirstAddressLine = "";
                currentLocationCountryName = "";
                currentLocationLocality = "";
                currentLocationSubLocality = "";
                Logger.info(TAG, "Unable to retrieve location name" + e.toString());
            }
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler.post(updateCurrentLocationBubbleRunnable);
        }
    };
    */

    public static LocationFragment newInstance() {
        return new LocationFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = getActivity().getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));

        locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
        mainHandler = new Handler();
        handlerThread = new HandlerThread("Background handler");
        handlerThread.start();
        // backgroundHandler = new Handler(handlerThread.getLooper());
        geocoder = new Geocoder(getContext(), Locale.getDefault());
        zoom = true;

        // retrieve the accent color to be used for the paint
        accentColor = AccentColor.defaultColor().color();
        ((BaseActivity) getContext()).injectJava(AccentColorController.class).accentColorForJava(new AccentColorCallback() {
            @Override
            public void color(AccentColor color) {
                selectedLocationPin.setTextColor(color.color());
                marker = null;
                accentColor = color.color();
            }
        }, EventContext.Implicits$.MODULE$.global());
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup viewGroup, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_location, viewGroup, false);

        toolbar = ViewUtils.getView(view, R.id.t_location_toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() == null) {
                    return;
                }
                getControllerFactory().getLocationController().hideShareLocation(null);
            }
        });

        toolbarTitle = ViewUtils.getView(view, R.id.tv__location_toolbar__title);

        selectedLocationBackground = ViewUtils.getView(view, R.id.iv__selected_location__background);
        selectedLocationPin = ViewUtils.getView(view, R.id.gtv__selected_location__pin);

        selectedLocationDetails = ViewUtils.getView(view, R.id.ll_selected_location_details);
        selectedLocationDetails.setVisibility(View.INVISIBLE);

        touchRegisteringFrameLayout = ViewUtils.getView(view, R.id.trfl_location_touch_registerer);
        touchRegisteringFrameLayout.setTouchCallback(this);

        requestCurrentLocationButton = ViewUtils.getView(view, R.id.gtv__location__current__button);
        requestCurrentLocationButton.setOnClickListener(this);

        sendCurrentLocationButton = ViewUtils.getView(view, R.id.ttv__location_send_button);
        sendCurrentLocationButton.setOnClickListener(this);

        selectedLocationAddress = ViewUtils.getView(view, R.id.ttv__location_address);

        map = ViewUtils.getView(view, R.id.mv_map);
        map.setTileSource(TileSourceFactory.MAPNIK);

        return view;
    }

    private final Callback callback = new Callback<ConversationController.ConversationChange>() {
        @Override
        public void callback(ConversationController.ConversationChange change) {
            if (change.toConvId() != null) {
                inject(ConversationController.class).withConvLoaded(change.toConvId(), new Callback<ConversationData>() {
                    @Override
                    public void callback(ConversationData conversationData) {
                        toolbarTitle.setText(conversationData.getName());
                    }
                });
            }
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        if (hasLocationPermission()) {
            updateLastKnownLocation();
            if (!isLocationServicesEnabled()) {
                showLocationServicesDialog();
            }
            requestCurrentLocationButton.setVisibility(View.VISIBLE);
        } else {
            requestLocationPermission();
            checkIfLocationServicesEnabled = true;
            requestCurrentLocationButton.setVisibility(View.GONE);
        }

        inject(ConversationController.class).addConvChangedCallback(callback);
    }

    @Override
    public void onResume() {
        super.onResume();
        map.onResume();

        inject(ConversationController.class).withCurrentConvName(new Callback<String>() {
            @Override
            public void callback(String convName) {
                toolbarTitle.setText(convName);
            }
        });

        if (!getControllerFactory().getUserPreferencesController().hasPerformedAction(IUserPreferencesController.SEND_LOCATION_MESSAGE)) {
            getControllerFactory().getUserPreferencesController().setPerformedAction(IUserPreferencesController.SEND_LOCATION_MESSAGE);
            Toast.makeText(getContext(), R.string.location_sharing__tip, Toast.LENGTH_LONG).show();
        }
        if (hasLocationPermission()) {
            if (locationManager != null) {
                startLocationManagerListeningForCurrentLocation();
            }
        }
    }

    @Override
    public void onPause() {
        stopLocationManagerListeningForCurrentLocation();
        super.onPause();
        map.onPause();
    }

    @Override
    public void onStop() {
        inject(ConversationController.class).removeConvChangedCallback(callback);
        super.onStop();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    @SuppressWarnings("ResourceType")
    @SuppressLint("MissingPermission")
    private void startLocationManagerListeningForCurrentLocation() {
        Logger.info(TAG,"startLocationManagerListeningForCurrentLocation");
        if (locationManager != null && hasLocationPermission()) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
        }
    }

    @SuppressWarnings("ResourceType")
    @SuppressLint("MissingPermission")
    private void stopLocationManagerListeningForCurrentLocation() {
        Logger.info(TAG,"stopLocationManagerListeningForCurrentLocation");
        if (locationManager != null && hasLocationPermission()) {
            locationManager.removeUpdates(this);
        }
    }

    private boolean isLocationServicesEnabled() {
        if (!hasLocationPermission()) {
            return false;
        }
        // We are creating a local locationManager here, as it's not sure we already have one
        LocationManager locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        boolean gpsEnabled;
        boolean netEnabled;

        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            gpsEnabled = false;
        }

        try {
            netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            netEnabled = false;
        }
        return netEnabled || gpsEnabled;
    }

    private void showLocationServicesDialog() {
        ViewUtils.showAlertDialog(getContext(),
                                  R.string.location_sharing__enable_system_location__title,
                                  R.string.location_sharing__enable_system_location__message,
                                  R.string.location_sharing__enable_system_location__confirm,
                                  R.string.location_sharing__enable_system_location__cancel,
                                  new DialogInterface.OnClickListener() {
                                      @Override
                                      public void onClick(DialogInterface dialog, int which) {
                                          Intent myIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                          getContext().startActivity(myIntent);
                                      }
                                  },
                                  null);
    }

    /*
    private void updateCurrentLocationName(int zoom) {
        if (zoom >= 12) {
            // Local address
            if (!StringUtils.isBlank(currentLocationFirstAddressLine)) {
                currentLocationName = currentLocationFirstAddressLine;
            } else if (!StringUtils.isBlank(currentLocationSubLocality)) {
                currentLocationName = currentLocationSubLocality;
            } else if (!StringUtils.isBlank(currentLocationLocality)) {
                currentLocationName = currentLocationLocality;
            } else {
                currentLocationName = currentLocationCountryName;
            }
        } else if (zoom >= 6) {
            // City-ish
            if (!StringUtils.isBlank(currentLocationSubLocality)) {
                currentLocationName = currentLocationSubLocality;
            } else if (!StringUtils.isBlank(currentLocationLocality)) {
                currentLocationName = currentLocationLocality;
            } else {
                currentLocationName = currentLocationCountryName;
            }
        } else {
            // Country
            currentLocationName = currentLocationCountryName;
        }
    }
    */

    @Override
    public void onInterceptTouchEvent(MotionEvent event) {
        animateToCurrentLocation = false;
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.gtv__location__current__button:
                animateToCurrentLocation = true;
                zoom = true;
                if (hasLocationPermission()) {
                    updateLastKnownLocation();
                } else {
                    requestLocationPermission();
                }
                break;
            case R.id.ttv__location_send_button:
                MessageContent.Location location;
                if (map == null) {
                    if (!BuildConfig.DEBUG) {
                        return;
                    }
                    location = new MessageContent.Location(0.0f, 0.0f, "", 0);
                } else {
                    IGeoPoint center = map.getMapCenter();
                    location = new MessageContent.Location((float) center.getLatitude(),
                        (float) center.getLongitude(),
                        currentLocationName,
                        (int) map.getZoomLevelDouble());
                }

                getControllerFactory().getLocationController().hideShareLocation(location);
                ZMessaging.currentGlobal().trackingService().contribution(new ContributionEvent.Action("location"), Option.empty()); //TODO use lazy val when in scala
                break;
        }
    }

    @SuppressWarnings("ResourceType")
    @SuppressLint("MissingPermission")
    private void updateLastKnownLocation() {
        if (locationManager != null) {
            currentLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        }
        if (currentLocation != null) {
            onLocationChanged(currentLocation);
        }
    }

    /*
    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        Logger.info(TAG,"onCameraChange");
        animating = false;
        currentLatLng = cameraPosition.target;
        currentLocationName = "";

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                selectedLocationAddress.setVisibility(View.INVISIBLE);
            }
        }, LOCATION_REQUEST_TIMEOUT_MS);
        backgroundHandler.removeCallbacksAndMessages(null);
        backgroundHandler.post(retrieveCurrentLocationNameRunnable);
    }
    */

    /*
    @Override
    public void onMapReady(GoogleMap googleMap) {
        Logger.info(TAG,"onMapReady");
        map = googleMap;
        map.getUiSettings().setMyLocationButtonEnabled(false);
        try {
            map.setMyLocationEnabled(false);
        } catch (SecurityException se) {
            // ignore
        }
        if (currentLocation != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude()), DEFAULT_MAP_ZOOM_LEVEL));
            animateTocurrentLocation = false;
            onLocationChanged(currentLocation);
        }
        map.setOnCameraChangeListener(this);
    }
    */

    // FIXME
    @Override
    public void onLocationChanged(Location location) {
        Float distanceToCurrent = (currentLocation == null) ? 0 : location.distanceTo(currentLocation);
        Logger.info(TAG,"onLocationChanged, lat" + location.getLatitude() + ", lon=" + location.getLongitude() + ", accuracy=" + location.getAccuracy() + ", distanceToCurrent=" + distanceToCurrent);

        float distanceFromCenterOfScreen = Float.MAX_VALUE;
        if (map != null) {
            IGeoPoint center = map.getMapCenter();
            float[] distance = new float[1];
            Location.distanceBetween(center.getLatitude(),
                                     center.getLongitude(),
                                     location.getLatitude(),
                                     location.getLongitude(),
                                     distance);
            distanceFromCenterOfScreen = distance[0];
            Logger.info(TAG,"current location distance from map center" + distance[0]);
        }

        currentLocation = location;
        if (map != null) {
            /*
            map.clear();
            LatLng position = new LatLng(location.getLatitude(), location.getLongitude());
            map.addMarker(new MarkerOptions()
                              .position(position)
                              .icon(BitmapDescriptorFactory.fromBitmap(getMarker()))
                              .anchor(0.5f, 0.5f));
            */
            if (animateToCurrentLocation && distanceFromCenterOfScreen > DEFAULT_MIMIMUM_CAMERA_MOVEMENT) {
                IMapController controller = map.getController();
                if (zoom || animating) {
                    controller.setZoom(DEFAULT_MAP_ZOOM_LEVEL); // FIXME
                    controller.animateTo(new GeoPoint(currentLocation.getLatitude(), currentLocation.getLongitude()));
                    animating = true;
                    zoom = false;
                } else {
                    controller.animateTo(new GeoPoint(currentLocation.getLatitude(), currentLocation.getLongitude()));
                }
            }
        }
    }

    /*
    private void setTextAddressBubble(String name) {
        if (StringUtils.isBlank(name)) {
            selectedLocationDetails.setVisibility(View.INVISIBLE);
            selectedLocationBackground.setVisibility(View.VISIBLE);
            selectedLocationPin.setVisibility(View.VISIBLE);
        } else {
            selectedLocationAddress.setText(name);
            selectedLocationAddress.setVisibility(View.VISIBLE);
            selectedLocationDetails.requestLayout();
            selectedLocationDetails.setVisibility(View.VISIBLE);
            selectedLocationBackground.setVisibility(View.INVISIBLE);
            selectedLocationPin.setVisibility(View.INVISIBLE);
        }
    }
    */

    /*
    private Bitmap getMarker() {
        if (marker != null) {
            return marker;
        }
        int size = getResources().getDimensionPixelSize(R.dimen.share_location__current_location_marker__size);
        int outerCircleRadius = getResources().getDimensionPixelSize(R.dimen.share_location__current_location_marker__outer_ring_radius);
        int midCircleRadius = getResources().getDimensionPixelSize(R.dimen.share_location__current_location_marker__mid_ring_radius);
        int innerCircleRadius = getResources().getDimensionPixelSize(R.dimen.share_location__current_location_marker__inner_ring_radius);

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(accentColor);
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(getResources().getInteger(R.integer.share_location__current_location_marker__outer_ring_alpha));
        canvas.drawCircle(size / 2, size / 2, outerCircleRadius, paint);
        paint.setAlpha(getResources().getInteger(R.integer.share_location__current_location_marker__mid_ring_alpha));
        canvas.drawCircle(size / 2, size / 2, midCircleRadius, paint);
        paint.setAlpha(getResources().getInteger(R.integer.share_location__current_location_marker__inner_ring_alpha));
        canvas.drawCircle(size / 2, size / 2, innerCircleRadius, paint);
        marker = bitmap;
        return marker;
    }
    */

    @Override
    public boolean onBackPressed() {
        if (getControllerFactory() == null || getControllerFactory().isTornDown()) {
            return false;
        }
        getControllerFactory().getLocationController().hideShareLocation(null);
        return true;
    }

    /*
    @Override
    public void onConnected(Bundle bundle) {
        Logger.info(TAG,"onConnected");

        if (hasLocationPermission()) {
            animateTocurrentLocation = true;
            startPlayServicesListeningForCurrentLocation();
        } else {
            requestLocationPermission();
        }
    }
    */

    /*
    @Override
    public void onConnectionSuspended(int i) {
        Logger.info(TAG,"onConnectionSuspended");
        // goodbye
    }
    */

    /*
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        // fallback to LocationManager
        Logger.error(TAG,"Google API Client connection failed");
        googleApiClient.unregisterConnectionFailedListener(this);
        googleApiClient.unregisterConnectionCallbacks(this);
        googleApiClient = null;
        locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
        if (hasLocationPermission()) {
            startLocationManagerListeningForCurrentLocation();
        } else {
            requestLocationPermission();
        }
    }
    */

    private boolean hasLocationPermission() {
        return inject(PermissionsService.class).checkPermission(ACCESS_FINE_LOCATION);
    }

    private void requestLocationPermission() {
        inject(PermissionsService.class).requestPermission(ACCESS_FINE_LOCATION, new PermissionsService.PermissionsCallback() {
            @Override
            public void onPermissionResult(boolean granted) {
                if (getActivity() == null) {
                    return;
                }
                if (granted) {
                    requestCurrentLocationButton.setVisibility(View.VISIBLE);
                    zoom = true;
                    updateLastKnownLocation();
                    if (locationManager != null) {
                        startLocationManagerListeningForCurrentLocation();
                    }
                    if (checkIfLocationServicesEnabled) {
                        checkIfLocationServicesEnabled = false;
                        if (!isLocationServicesEnabled()) {
                            showLocationServicesDialog();
                        }
                    }
                } else {
                    Toast.makeText(getContext(), R.string.location_sharing__permission_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public interface Container {

    }
}
