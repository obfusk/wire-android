/**
 * Wire
 * Copyright (C) 2018, 2021 Wire Swiss GmbH
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
package com.waz.zclient.pages.main.conversation

import android.Manifest
import android.content.{Context, DialogInterface, Intent}
import android.graphics.drawable.BitmapDrawable
import android.graphics.{Bitmap, Canvas, Paint}
import android.location.{Geocoder, Location, LocationListener, LocationManager}
import android.os.{Bundle, Handler, HandlerThread, Looper}
import android.provider.Settings
import android.view.{LayoutInflater, MotionEvent, View, ViewGroup}
import android.widget.{LinearLayout, TextView, Toast}
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import com.waz.api.MessageContent
import com.waz.model.{AccentColor, ConversationData}
import com.waz.permissions.PermissionsService
import com.waz.service.ZMessaging
import com.waz.service.tracking.ContributionEvent
import com.waz.zclient.common.controllers.global.{AccentColorCallback, AccentColorController}
import com.waz.zclient.controllers.userpreferences.IUserPreferencesController
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.conversation.ConversationController.ConversationChange
import com.waz.zclient.core.logging.Logger.info
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.ui.text.GlyphTextView
import com.waz.zclient.ui.views.TouchRegisteringFrameLayout
import com.waz.zclient.utils.{Callback, StringUtils, ViewUtils}
import com.waz.zclient.{BaseActivity, BuildConfig, OnBackPressedListener, R}
import com.wire.signals.EventContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.{MapListener, ScrollEvent, ZoomEvent}
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

import java.util._

// FIXME: use more idiomatic scala (Option, lazy val etc.)
class LocationFragment extends BaseFragment[LocationFragment.Container]
  with LocationListener
  with MapListener
  with TouchRegisteringFrameLayout.TouchCallback
  with OnBackPressedListener
  with View.OnClickListener {

  import LocationFragment._

  private var toolbar: Toolbar = null
  private var map: MapView = null
  private var selectedLocationBackground: View = null
  private var selectedLocationPin: GlyphTextView = null
  private var selectedLocationDetails: LinearLayout = null
  private var selectedLocationAddress: TextView = null
  private var touchRegisteringFrameLayout: TouchRegisteringFrameLayout = null
  private var requestCurrentLocationButton: TextView = null
  private var sendCurrentLocationButton: TextView = null
  private var toolbarTitle: TextView = null
  private var marker: Bitmap = null

  private var locationManager: LocationManager = null
  private var currentLocation: Location = null

  private var currentLocationCountryName: String = null
  private var currentLocationLocality: String = null
  private var currentLocationSubLocality: String = null
  private var currentLocationFirstAddressLine: String = null
  private var currentLocationName: String = null

  private var animateToCurrentLocation = false
  private var checkIfLocationServicesEnabled = false

  private var mainHandler: Handler = null
  private var backgroundHandler: Handler = null
  private var handlerThread: HandlerThread = null
  private var geocoder: Geocoder = null

  private var accentColor = 0

  private val updateCurrentLocationBubbleRunnable = new Runnable {
    override def run(): Unit = {
      if (getActivity != null && getContainer != null) {
        updateCurrentLocationName(map.getZoomLevelDouble.toInt)
        setTextAddressBubble(currentLocationName)
      }
    }
  }

  private val retrieveCurrentLocationNameRunnable = new Runnable {
    override def run(): Unit = {
      currentLocationFirstAddressLine = ""
      currentLocationCountryName = ""
      currentLocationLocality = ""
      currentLocationSubLocality = ""
      try {
        val center = map.getMapCenter
        val addresses = geocoder.getFromLocation(center.getLatitude, center.getLongitude, 1)
        if (addresses != null && addresses.size > 0) {
          val adr = addresses.get(0)
          currentLocationFirstAddressLine = if (adr.getMaxAddressLineIndex >= 0) adr.getAddressLine(0) else ""
          currentLocationCountryName = adr.getCountryName
          currentLocationLocality = adr.getLocality
          currentLocationSubLocality = adr.getSubLocality
        }
      } catch {
        case e: Exception => info(TAG, s"Unable to retrieve location name: $e")
      }
      mainHandler.removeCallbacksAndMessages(null)
      mainHandler.post(updateCurrentLocationBubbleRunnable)
    }
  }

  private val callback: Callback[ConversationChange] = new Callback[ConversationChange] {
    override def callback(change: ConversationChange): Unit = {
      if (change.toConvId != null) {
        inject(classOf[ConversationController]).withConvLoaded(change.toConvId, new Callback[ConversationData] {
          override def callback(conversationData: ConversationData): Unit = {
            toolbarTitle.setText(conversationData.getName())
          }
        })
      }
    }
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)

    val ctx = getActivity.getApplicationContext
    Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))

    locationManager = getActivity.getSystemService(Context.LOCATION_SERVICE).asInstanceOf[LocationManager]
    mainHandler = new Handler(Looper.myLooper)
    handlerThread = new HandlerThread("Background handler")
    handlerThread.start()
    backgroundHandler = new Handler(handlerThread.getLooper)
    geocoder = new Geocoder(getContext, Locale.getDefault)

    // retrieve the accent color to be used for the paint
    accentColor = AccentColor.defaultColor.color
    getContext.asInstanceOf[BaseActivity].injectJava(classOf[AccentColorController])
      .accentColorForJava(new AccentColorCallback {
        override def color(color: AccentColor): Unit = {
          selectedLocationPin.setTextColor(color.color)
          marker = null
          accentColor = color.color
        }
      }, EventContext.Implicits.global)
  }

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View = {
    val view = inflater.inflate(R.layout.fragment_location, viewGroup, false)

    toolbar = ViewUtils.getView(view, R.id.t_location_toolbar)
    toolbar.setNavigationOnClickListener(new View.OnClickListener {
      override def onClick(v: View): Unit = {
        if (getActivity != null) {
          getControllerFactory.getLocationController.hideShareLocation(null)
        }
      }
    })

    toolbarTitle = ViewUtils.getView(view, R.id.tv__location_toolbar__title)

    selectedLocationBackground = ViewUtils.getView(view, R.id.iv__selected_location__background)
    selectedLocationPin = ViewUtils.getView(view, R.id.gtv__selected_location__pin)

    selectedLocationDetails = ViewUtils.getView(view, R.id.ll_selected_location_details)
    selectedLocationDetails.setVisibility(View.INVISIBLE)

    touchRegisteringFrameLayout = ViewUtils.getView(view, R.id.trfl_location_touch_registerer)
    touchRegisteringFrameLayout.setTouchCallback(this)

    requestCurrentLocationButton = ViewUtils.getView(view, R.id.gtv__location__current__button)
    requestCurrentLocationButton.setOnClickListener(this)

    sendCurrentLocationButton = ViewUtils.getView(view, R.id.ttv__location_send_button)
    sendCurrentLocationButton.setOnClickListener(this)

    selectedLocationAddress = ViewUtils.getView(view, R.id.ttv__location_address)

    map = ViewUtils.getView(view, R.id.mv_map)
    map.setTileSource(TileSourceFactory.MAPNIK)
    map.addMapListener(this)
    map.getController.setZoom(INIT_MAP_ZOOM_LEVEL)

    view
  }

  override def onStart(): Unit = {
    super.onStart()
    if (hasLocationPermission()) {
      updateLastKnownLocation()
      if (!isLocationServicesEnabled) {
        showLocationServicesDialog()
      }
      requestCurrentLocationButton.setVisibility(View.VISIBLE)
    } else {
      requestLocationPermission()
      checkIfLocationServicesEnabled = true
      requestCurrentLocationButton.setVisibility(View.GONE)
    }
    inject(classOf[ConversationController]).addConvChangedCallback(callback)
  }

  override def onResume(): Unit = {
    super.onResume()
    map.onResume()
    inject(classOf[ConversationController]).withCurrentConvName(new Callback[String] {
      override def callback(convName: String): Unit = {
        toolbarTitle.setText(convName)
      }
    })
    if (!getControllerFactory.getUserPreferencesController.hasPerformedAction(IUserPreferencesController.SEND_LOCATION_MESSAGE)) {
      getControllerFactory.getUserPreferencesController.setPerformedAction(IUserPreferencesController.SEND_LOCATION_MESSAGE)
      Toast.makeText(getContext, R.string.location_sharing__tip, Toast.LENGTH_LONG).show()
    }
    startLocationManagerListeningForCurrentLocation()
  }

  override def onPause(): Unit = {
    stopLocationManagerListeningForCurrentLocation()
    super.onPause()
    map.onPause()
  }

  override def onStop(): Unit = {
    inject(classOf[ConversationController]).removeConvChangedCallback(callback)
    super.onStop()
  }

  override def onProviderEnabled(provider: String): Unit = {}
  override def onProviderDisabled(provider: String): Unit = {}

  private def startLocationManagerListeningForCurrentLocation(): Unit = {
    info(TAG, "startLocationManagerListeningForCurrentLocation")
    if (locationManager != null && hasLocationPermission()) {
      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, this)
    }
  }

  private def stopLocationManagerListeningForCurrentLocation(): Unit = {
    info(TAG, "stopLocationManagerListeningForCurrentLocation")
    if (locationManager != null && hasLocationPermission()) {
      locationManager.removeUpdates(this)
    }
  }

  // FIXME: NETWORK_PROVIDER does not seem to be used
  private def isLocationServicesEnabled(): Boolean = {
    if (!hasLocationPermission()) {
      return false
    }
    // We are creating a local locationManager here, as it's not sure we already have one
    val locationManager = getActivity.getSystemService(Context.LOCATION_SERVICE).asInstanceOf[LocationManager]
    if (locationManager == null) {
      return false
    }
    val gpsEnabled = try {
      locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch {
      case _: Exception => false
    }
    val netEnabled = try {
      locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch {
      case _: Exception => false
    }
    netEnabled || gpsEnabled
  }

  private def showLocationServicesDialog(): Unit = {
    ViewUtils.showAlertDialog(
      getContext,
      R.string.location_sharing__enable_system_location__title,
      R.string.location_sharing__enable_system_location__message,
      R.string.location_sharing__enable_system_location__confirm,
      R.string.location_sharing__enable_system_location__cancel,
      new DialogInterface.OnClickListener {
        override def onClick(dialog: DialogInterface, which: Int): Unit = {
          getContext.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
      },
      null
    )
  }

  // FIXME: are zoom levels compatible?
  private def updateCurrentLocationName(zoom: Int): Unit = {
    currentLocationName = if (zoom >= 12) {
      // Local address
      if (!StringUtils.isBlank(currentLocationFirstAddressLine)) {
        currentLocationFirstAddressLine
      } else if (!StringUtils.isBlank(currentLocationSubLocality)) {
        currentLocationSubLocality
      } else if (!StringUtils.isBlank(currentLocationLocality)) {
        currentLocationLocality
      } else {
        currentLocationCountryName
      }
    } else if (zoom >= 6) {
      // City-ish
      if (!StringUtils.isBlank(currentLocationSubLocality)) {
        currentLocationSubLocality
      } else if (!StringUtils.isBlank(currentLocationLocality)) {
        currentLocationLocality
      } else {
        currentLocationCountryName
      }
    } else {
      // Country
      currentLocationCountryName
    }
  }

  override def onInterceptTouchEvent(event: MotionEvent): Unit = {
    animateToCurrentLocation = false
  }

  override def onClick(view: View): Unit = {
    view.getId match {
      case R.id.gtv__location__current__button =>
        animateToCurrentLocation = true
        if (hasLocationPermission()) {
          updateLastKnownLocation()
        } else {
          requestLocationPermission()
        }
      case R.id.ttv__location_send_button =>
        val location = if (map == null) {
          if (!BuildConfig.DEBUG) {
            return
          }
          new MessageContent.Location(0.0f, 0.0f, "", 0)
        } else {
          val center = map.getMapCenter
          // NB: longitude before latitude in this API
          new MessageContent.Location(
            center.getLongitude.toFloat,
            center.getLatitude.toFloat,
            currentLocationName,
            map.getZoomLevelDouble.toInt
          )
        }
        getControllerFactory.getLocationController.hideShareLocation(location)
        // TODO: use lazy val when in scala
        ZMessaging.currentGlobal.trackingService.contribution(
          new ContributionEvent.Action("location"),
          Option.empty
        )
      case _ =>
    }
  }

  private def updateLastKnownLocation(): Unit = {
    if (locationManager != null) {
      val location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
      if (location != null) {
        onLocationChanged(location)
      }
    }
  }

  override def onScroll(event: ScrollEvent): Boolean = {
    info(TAG, "onScroll")
    onCameraChange()
    true // ???
  }

  override def onZoom(event: ZoomEvent): Boolean = {
    info(TAG, "onZoom")
    onCameraChange()
    true // ???
  }

  private def onCameraChange(): Unit = {
    currentLocationName = ""
    mainHandler.postDelayed(new Runnable {
      override def run(): Unit = {
        selectedLocationAddress.setVisibility(View.INVISIBLE)
      }
    }, LOCATION_REQUEST_TIMEOUT_MS)
    backgroundHandler.removeCallbacksAndMessages(null)
    backgroundHandler.post(retrieveCurrentLocationNameRunnable)
  }

  // FIXME: what's the expected behaviour?
  override def onLocationChanged(location: Location): Unit = {
    val distanceToCurrent = if (currentLocation == null) 0 else location.distanceTo(currentLocation)
    info(TAG, s"onLocationChanged, lat=${location.getLatitude}, lon=${location.getLongitude}, accuracy=${location.getAccuracy}, distanceToCurrent=$distanceToCurrent")
    var distanceFromCenterOfScreen = Float.MaxValue
    if (map != null) {
      val center = map.getMapCenter
      val distance = new Array[Float](1)
      Location.distanceBetween(
        center.getLatitude,
        center.getLongitude,
        location.getLatitude,
        location.getLongitude,
        distance
      )
      distanceFromCenterOfScreen = distance(0)
      info(TAG, s"current location distance from map center: ${distance(0)}")

      if (currentLocation == null || distanceToCurrent != 0) {
        map.getOverlays.clear()
        val m = new Marker(map)
        m.setPosition(new GeoPoint(location.getLatitude, location.getLongitude))
        m.setIcon(new BitmapDrawable(getResources, getMarker()))
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.getOverlays.add(m)
        map.invalidate()
      }
    }
    if (currentLocation == null && !animateToCurrentLocation) {
      info(TAG, "zooming in to current location")
      map.getController.setZoom(DEFAULT_MAP_ZOOM_LEVEL)
      animateToCurrentLocation = true
    }
    currentLocation = location
    if (map != null) {
      if (animateToCurrentLocation && distanceFromCenterOfScreen > DEFAULT_MINIMUM_CAMERA_MOVEMENT) {
        info(TAG, "moving to current location")
        val controller = map.getController
        val speed = Configuration.getInstance.getAnimationSpeedDefault
        controller.animateTo(new GeoPoint(currentLocation.getLatitude, currentLocation.getLongitude))
        animateToCurrentLocation = false
      }
    }
  }

  private def setTextAddressBubble(name: String): Unit = {
    if (StringUtils.isBlank(name)) {
      selectedLocationDetails.setVisibility(View.INVISIBLE)
      selectedLocationBackground.setVisibility(View.VISIBLE)
      selectedLocationPin.setVisibility(View.VISIBLE)
    } else {
      selectedLocationAddress.setText(name)
      selectedLocationAddress.setVisibility(View.VISIBLE)
      selectedLocationDetails.requestLayout()
      selectedLocationDetails.setVisibility(View.VISIBLE)
      selectedLocationBackground.setVisibility(View.INVISIBLE)
      selectedLocationPin.setVisibility(View.INVISIBLE)
    }
  }

  private def getMarker(): Bitmap = {
    if (marker != null) {
      return marker
    }
    val size = getResources.getDimensionPixelSize(R.dimen.share_location__current_location_marker__size)
    val outerCircleRadius = getResources.getDimensionPixelSize(R.dimen.share_location__current_location_marker__outer_ring_radius)
    val midCircleRadius = getResources.getDimensionPixelSize(R.dimen.share_location__current_location_marker__mid_ring_radius)
    val innerCircleRadius = getResources.getDimensionPixelSize(R.dimen.share_location__current_location_marker__inner_ring_radius)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = new Canvas(bitmap)
    val paint = new Paint()
    paint.setColor(accentColor)
    paint.setAntiAlias(true)
    paint.setStyle(Paint.Style.FILL)
    paint.setAlpha(getResources.getInteger(R.integer.share_location__current_location_marker__outer_ring_alpha))
    canvas.drawCircle(
      (size / 2).toFloat,
      (size / 2).toFloat,
      outerCircleRadius.toFloat,
      paint
    )
    paint.setAlpha(getResources.getInteger(R.integer.share_location__current_location_marker__mid_ring_alpha))
    canvas.drawCircle(
      (size / 2).toFloat,
      (size / 2).toFloat,
      midCircleRadius.toFloat,
      paint
    )
    paint.setAlpha(getResources.getInteger(R.integer.share_location__current_location_marker__inner_ring_alpha))
    canvas.drawCircle(
      (size / 2).toFloat,
      (size / 2).toFloat,
      innerCircleRadius.toFloat,
      paint
    )
    marker = bitmap
    marker
  }

  override def onBackPressed(): Boolean = {
    if (getControllerFactory == null || getControllerFactory.isTornDown) {
      false
    } else {
      getControllerFactory.getLocationController.hideShareLocation(null)
      true
    }
  }

  private def hasLocationPermission(): Boolean = {
    inject(classOf[PermissionsService]).checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
  }

  private def requestLocationPermission(): Unit = {
    inject(classOf[PermissionsService]).requestPermission(
      Manifest.permission.ACCESS_FINE_LOCATION,
      new PermissionsService.PermissionsCallback {
        override def onPermissionResult (granted: Boolean): Unit = {
          if (getActivity == null) {
            return
          }
          if (granted) {
            requestCurrentLocationButton.setVisibility(View.VISIBLE)
            updateLastKnownLocation()
            if (locationManager != null) {
              startLocationManagerListeningForCurrentLocation()
            }
            if (checkIfLocationServicesEnabled) {
              checkIfLocationServicesEnabled = false
              if (!isLocationServicesEnabled) {
                showLocationServicesDialog()
              }
            }
          } else {
            Toast.makeText(getContext, R.string.location_sharing__permission_error, Toast.LENGTH_SHORT).show()
          }
        }
      })
  }
}

object LocationFragment {
  trait Container

  val TAG = "LocationFragment"

  private val INIT_MAP_ZOOM_LEVEL = 1.0
  private val DEFAULT_MAP_ZOOM_LEVEL = 15f // FIXME: are zoom levels compatible?
  private val DEFAULT_MINIMUM_CAMERA_MOVEMENT = 5f // FIXME
  private val LOCATION_REQUEST_TIMEOUT_MS = 1500

  def newInstance(): LocationFragment = new LocationFragment()
}
