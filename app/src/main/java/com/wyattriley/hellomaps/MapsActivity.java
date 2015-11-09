package com.wyattriley.hellomaps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import android.location.Location;
import android.support.v4.content.ContextCompat;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback,
                                                              GoogleApiClient.ConnectionCallbacks,
                                                              GoogleApiClient.OnConnectionFailedListener,
                                                              LocationListener
{
    // good enough accuracy (should use ~15, higher for test) - and/or spread across small bins
    final Integer MAX_ACCURACY_FOR_UPDATE = 25; // meters

    private GoogleMap mMap;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private TelephonyManager mTelephonyManager;
    private Queue<Circle> mCircleQRecent;
    private Queue<Circle> mCircleQRecentSignal;

    private DataFilterByLatLng mSignalData;

    final int MAX_RECENT_CIRCLES = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        createLocationRequest();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        mTelephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        mCircleQRecent = new LinkedList<>();
        mCircleQRecentSignal = new LinkedList<>();
        if (mSignalData == null)
        {   // only create it the first time, not on portrait/landscape/app-recreate
            mSignalData = new DataFilterByLatLng();
        }
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        // Enable MyLocation Layer of Google Map
        mMap.setMyLocationEnabled(true);

        // Get LocationManager object from System Service LOCATION_SERVICE

        // OLD style, for first fix
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Get Current Location
        Location oldLocation = new Location("gps");
        if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)) {
            oldLocation = locationManager.getLastKnownLocation("gps");
        }

        // Add a marker in at My Location and move the camera
        LatLng myLatLng = new LatLng(oldLocation.getLatitude(), oldLocation.getLongitude());
        mMap.addMarker(new MarkerOptions().position(myLatLng).title(
                "My Last Known Location on Map Ready" /* at accuracy " + mLastLocation.getAccuracy() */));
        mMap.moveCamera(CameraUpdateFactory.newLatLng(myLatLng));
        mMap.moveCamera(CameraUpdateFactory.zoomTo(19));

        mMap.addCircle(new CircleOptions()
                .center(myLatLng)
                .radius(oldLocation.getAccuracy()));

        mSignalData.drawShapes(mMap);// refresh after screen orientation change - todo figure out this refresh stuff better
    }

    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(1000);
        mLocationRequest.setFastestInterval(1000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }
    public void onLocationChanged(Location location) {
        LatLng myLatLng = new LatLng(location.getLatitude(), location.getLongitude());

        updateRecentCircles(location);

        if (location.getAccuracy() < MAX_ACCURACY_FOR_UPDATE)
        {
            int iGreenLevel = getLteSignalAsGreenLevel();
            if(location.getAccuracy() > MAX_ACCURACY_FOR_UPDATE)
                return; // todo - overlay some text on the google map for status

            int iWeight = 1; // todo - improve crude weighting?

            if(location.getAccuracy() < MAX_ACCURACY_FOR_UPDATE / 2)
                iWeight = 4;

            mSignalData.AddData(location, iWeight, iGreenLevel);
            mSignalData.drawShapes(mMap);

            mCircleQRecentSignal.add(
                    mMap.addCircle(new CircleOptions()
                            .strokeWidth(2)
                            .strokeColor(Color.GRAY)
                            .center(myLatLng)
                            .radius(location.getAccuracy())
                            .fillColor(Color.argb(64, 255 - iGreenLevel, iGreenLevel, 0))));

            if (mCircleQRecentSignal.size() > MAX_RECENT_CIRCLES)
            {
                mCircleQRecentSignal.remove().remove();
            }
        }
    }

    protected void updateRecentCircles(Location location)
    {
        LatLng myLatLng = new LatLng(location.getLatitude(), location.getLongitude());

        mCircleQRecent.add(
                mMap.addCircle(new CircleOptions()
                        .strokeWidth(1)
                        .center(myLatLng)
                        .radius(location.getAccuracy())));

        if (mCircleQRecent.size() > MAX_RECENT_CIRCLES)
        {
            mCircleQRecent.remove().remove(); // remove from list, then from map
        }
    }

    protected int getLteSignalAsGreenLevel()
    {
        int iGreenLevel = 0;

        List<CellInfo> listCellInfo = mTelephonyManager.getAllCellInfo();
        Log.d("Strength", "Getting Cell Info");

        int minDbmReported = 9999;

        for (CellInfo cellInfo : listCellInfo) {
            if (cellInfo instanceof CellInfoLte) {
                CellSignalStrengthLte lte = ((CellInfoLte) cellInfo).getCellSignalStrength();

                int iDbm = lte.getDbm();
                Log.d("Strength", "lte strength: lte.getDbm() " +iDbm );

                if (iDbm < 100)
                {
                    Log.d("Signal", "Unexpectedly strong LTE getDbm signal (low) value: " + iDbm +
                                    " vs. range of ~800-1200 typically expected");
                }
                else if (iDbm < minDbmReported)
                {
                    minDbmReported = iDbm;
                }
            }
        }

        // convert strange units - apprently dBm * -10 is reported by API.
        if (minDbmReported < 9999) // something found
        {
            int iDbm = -minDbmReported/10;
            if (iDbm < -120) {
                iGreenLevel = 55;
            } else if (iDbm > -80) {
                iGreenLevel = 255;
            } else {
                iGreenLevel = 255 + (iDbm + 80) * 5;
            }
        }
        return iGreenLevel;
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d("?", "onStart fired ..............");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d("?", "onConnected - isConnected ...............: " + mGoogleApiClient.isConnected());
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }
}