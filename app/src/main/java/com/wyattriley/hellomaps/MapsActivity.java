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
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

import android.location.Location;
import android.support.v4.content.ContextCompat;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback,
                                                              GoogleApiClient.ConnectionCallbacks,
                                                              GoogleApiClient.OnConnectionFailedListener,
                                                              LocationListener
{
    // good enough accuracy (should use ~15, higher for test) - and/or spread across small bins
    private GoogleMap mMap;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private TelephonyManager mTelephonyManager;
    private Queue<Circle> mCircleQRecent;
    private Queue<Circle> mCircleQRecentSignal;

    private DataFilterByLatLng mSignalData;
    private float mCurrentZoom; // for redraw on zoom
    private LatLng mCurrentTarget; // for redraw on pan

    final int MAX_RECENT_CIRCLES = 2;
    final String FILENAME = "saved_data_map2";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Log.d("on", "onCreate");

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
        mSignalData = new DataFilterByLatLng();

        try
        {
            FileInputStream fis = openFileInput(FILENAME);
            ObjectInputStream ois = new ObjectInputStream(fis);
            mSignalData = (DataFilterByLatLng) ois.readObject();
            ois.close();
        }
        catch (java.io.IOException | ClassNotFoundException e)
        {
            Log.d("File", "Couldn't read data from " + FILENAME + " " + e.getMessage());
        }
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap)
    {
        Log.d("on", "onMapReady");
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

        //  move the camera and zoom to initial location
        LatLng myLatLng = new LatLng(oldLocation.getLatitude(), oldLocation.getLongitude());
        if ((mCurrentZoom != 0.0) &&
            (mCurrentTarget != null)) // todo: context-save these values so this actually works
        {
            mMap.moveCamera(CameraUpdateFactory.newLatLng(mCurrentTarget));
            mMap.moveCamera(CameraUpdateFactory.zoomTo(mCurrentZoom));
        }
        else {
            mMap.moveCamera(CameraUpdateFactory.newLatLng(myLatLng));
            mMap.moveCamera(CameraUpdateFactory.zoomTo(19));
        }

        mMap.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition)
            {
                if ((cameraPosition.zoom != mCurrentZoom)||
                    (cameraPosition.target != mCurrentTarget))
                {
                    mCurrentZoom = cameraPosition.zoom;
                    mCurrentTarget = cameraPosition.target;
                    mSignalData.drawShapes(mMap);
                }

            }
        });

        /* For debug - a starting Hello Maps marker

        mMap.addMarker(new MarkerOptions().position(myLatLng).title(
                "My Last Known Location on Map Ready" at accuracy " + mLastLocation.getAccuracy() ));

        mMap.addCircle(new CircleOptions()
                .center(myLatLng)
                .radius(oldLocation.getAccuracy()));*/

        mSignalData.drawShapes(mMap);// refresh after screen orientation change
    }

    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest()
                .setInterval(1000)
                .setFastestInterval(1000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    public void onLocationChanged(Location location) {
        Log.d("on", "onLocationChanged");

        if (mMap == null) // not ready yet
        {
            return;
        }

        LatLng myLatLng = new LatLng(location.getLatitude(), location.getLongitude());

        updateRecentCircles(location);

        int iGreenLevel = getLteSignalAsGreenLevel();

        if (mSignalData.AddData(location, iGreenLevel))
        {
            // some data was added
            mSignalData.drawShapes(mMap); //(mMap, location); todo finish this to update better

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
        if (listCellInfo == null)
        {
            Log.w("Telephony", "Telephony Mgr. returned a NULL list of cell info");
            return iGreenLevel;
        }

        int minDbmReported = 9999;

        for (CellInfo cellInfo : listCellInfo) {
            if (cellInfo instanceof CellInfoLte) {
                CellSignalStrengthLte lte = ((CellInfoLte) cellInfo).getCellSignalStrength();

                int iDbm = lte.getDbm();
                //Log.d("Strength", "lte strength: lte.getDbm() " +iDbm );

                if (iDbm < 100)
                {
                    Log.w("Signal", "Unexpectedly strong LTE getDbm signal (low) value: " + iDbm +
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
        Log.d("on", "onStart fired ..............");
        mGoogleApiClient.connect();
    }

    public void onPause() {
        super.onPause();
        Log.d("on", "onPause fired ..............");

        if (mGoogleApiClient.isConnected())
        {
            LocationServices.FusedLocationApi.removeLocationUpdates(
                    mGoogleApiClient, this);
        } // todo - why am I still getting onLocationChanged updates with screen blank?  check lifecycle

        try
        {
            FileOutputStream fos = openFileOutput(FILENAME, Context.MODE_PRIVATE);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(mSignalData);
            oos.close();
        }
        catch (java.io.IOException e)
        {
            Log.w("File", "Couldn't save data to " + FILENAME + " " + e.getMessage());
        }
    }

    @Override
    public void onConnected(Bundle bundle)
    {
        Log.d("on", "onConnected (GoogleApiClient) - isConnected ...............: " + mGoogleApiClient.isConnected());
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d("on", "onConnectedSuspended (GoogleApiClient) - isConnected ...............: " + mGoogleApiClient.isConnected());


    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }
}