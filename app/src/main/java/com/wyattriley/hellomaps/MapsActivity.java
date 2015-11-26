package com.wyattriley.hellomaps;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.AsyncTask;
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
import android.view.MotionEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private TelephonyManager mTelephonyManager;
    private Queue<Circle> mCircleQRecent;
    private Queue<Circle> mCircleQRecentSignal;
    private boolean mMapTrackCurrentLocation;

    private DataFilterByLatLng mSignalData; // todo - test putting this outside the activity, so it persists on screen rotation
    private boolean mSignalDataLoaded;
    private float mCurrentZoom; // for redraw on zoom
    private LatLng mCurrentTarget; // for redraw on pan

    final int MAX_RECENT_CIRCLES = 2;
    final String FILENAME = "saved_data_map_1";
    final int DEFAULT_ZOOM = 19;
    final String PREFS_MAP = "prefs_map";

    private class OpenFileTask extends AsyncTask<Void, Void, DataFilterByLatLng>
    {
        protected DataFilterByLatLng doInBackground(Void... v)
        {
            DataFilterByLatLng d = new DataFilterByLatLng();
            try
            {
                FileInputStream fis = openFileInput(FILENAME);
                d.readFromFile(fis);
                fis.close();
                /*
                ObjectInputStream ois = new ObjectInputStream(fis);
                d = (DataFilterByLatLng) ois.readObject();
                ois.close();
                */
            }
            catch (java.io.IOException e)
            {
                Log.d("File", "Couldn't read data from " + FILENAME + " " + e.getMessage());
            }

            return d;
        }

        protected void onPostExecute(DataFilterByLatLng dataFilterByLatLng)
        {
            // todo: handle a merge from a read-in-struct, to one learned while awaiting file load
            mSignalData.clearShapes();
            mSignalData = dataFilterByLatLng;
            mSignalDataLoaded = true;
            if (mMap != null) // if it's ready
            {
                mSignalData.drawShapes(mMap);
            }
        }
    }

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

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        mTelephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        mCircleQRecent = new LinkedList<>();
        mCircleQRecentSignal = new LinkedList<>();

        mSignalDataLoaded = false;
        mSignalData = new DataFilterByLatLng();
        new OpenFileTask().execute(); // load mSignalData, in other thread, to avoid delay

        mMapTrackCurrentLocation = false; // todo: make unstick on user-pan (not on track-pan)

        // Load settings
        SharedPreferences settings = getSharedPreferences(PREFS_MAP, 0);
        mCurrentZoom = settings.getFloat("currentZoom", DEFAULT_ZOOM);
        LatLng latLng = new LatLng(settings.getFloat("currentTargetLat", 0.0F),
                settings.getFloat("currentTargetLng", 0.0F));
        if (latLng.latitude != 0.0F) // has useful data
        {
            mCurrentTarget = latLng;
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
        if (mCurrentZoom != 0.0)
        {
            mMap.moveCamera(CameraUpdateFactory.zoomTo(mCurrentZoom));
        }
        else
        {
            mMap.moveCamera(CameraUpdateFactory.zoomTo(DEFAULT_ZOOM));
        }
        if (mCurrentTarget != null)
        {
            mMap.moveCamera(CameraUpdateFactory.newLatLng(mCurrentTarget));
        }
        else
        {
            mMap.moveCamera(CameraUpdateFactory.newLatLng(myLatLng));
        }

        mMap.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition)
            {
                // check if changed, and if so, save to check next time & redraw
                if ((cameraPosition.zoom != mCurrentZoom)||
                    (cameraPosition.target != mCurrentTarget)) {

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

        mMap.setOnMyLocationButtonClickListener(new GoogleMap.OnMyLocationButtonClickListener(){
            @Override
            public boolean onMyLocationButtonClick()
            {
                // toggle tracking mode
                mMapTrackCurrentLocation = !mMapTrackCurrentLocation;
                Log.d("Track", "changed to " + mMapTrackCurrentLocation);
                return false;
            }
        });

        mSignalData.drawShapes(mMap);// refresh after screen orientation change
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
            mSignalData.drawShapes(mMap, location);

            mCircleQRecentSignal.add(
                    mMap.addCircle(new CircleOptions()
                            .strokeWidth(2)
                            .strokeColor(Color.GRAY)
                            .center(myLatLng)
                            .radius(location.getAccuracy())
                            .fillColor(DataFilterByLatLng.colorFromGreenLevel(iGreenLevel))));

            if (mCircleQRecentSignal.size() > MAX_RECENT_CIRCLES)
            {
                mCircleQRecentSignal.remove().remove();
            }
        }
         if (mMapTrackCurrentLocation)
         {
             mMap.animateCamera(CameraUpdateFactory.newLatLng(myLatLng));
         }
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d("on", "onStart fired ..............");
        mGoogleApiClient.connect();
    }

    @Override
    public void onResume(){
        super.onResume();
        Log.d("on", "onResume fired......");
        startLocationUpdates(mGoogleApiClient, true);
    }

    public void onPause() {
        super.onPause();
        Log.d("on", "onPause fired ..............");

        if (mGoogleApiClient.isConnected())
        {
            LocationServices.FusedLocationApi.removeLocationUpdates(
                    mGoogleApiClient, this);
            startLocationUpdates(mGoogleApiClient, false); // slow updates
        }

        if (mSignalDataLoaded) // only write out, if data has been loaded from file - to avoid losing data on a write before the async read has completed
        {
            // todo: make a function out of this block
            // Todo: figure out how to do this in a different thread - like the read, but avoiding a collision with the read...
            // todo: think through whether this has race condition fails - e.g. this thread still writing while main thread tries to read...
            try {
                // write to temp file, then copy over to main file when complete

                String strTempFileName = FILENAME + "Temp";
                FileOutputStream fosTemp = openFileOutput(strTempFileName, Context.MODE_PRIVATE);
                mSignalData.writeToFile(fosTemp);
                fosTemp.close();

                String strFileNameOld = FILENAME + "Old";
                File old = getFileStreamPath(strFileNameOld);
                File to = getFileStreamPath(FILENAME);
                File from = getFileStreamPath(strTempFileName);

                if (to.exists()) {
                    if (!to.renameTo(old)) {
                        Log.e("File", "Couldn't rename to to old file on write");
                    }
                }
                if (!from.exists()) {
                    Log.e("File", "Can't find from file " + from.getPath());
                }
                if (!from.renameTo(to)) {
                    Log.e("File", "Couldn't rename file from-to-to on write. From: " + from.getName() + " To: " + to.getName());
                }

            } catch (java.io.IOException e) {
                Log.w("File", "Couldn't save data to " + FILENAME + " " + e.getMessage());
            }
        }
    }

    protected void onStop()
    {
        super.onStop();

        // Save settings
        SharedPreferences settings = getSharedPreferences(PREFS_MAP, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putFloat("currentZoom", mCurrentZoom);
        if (mCurrentTarget != null) {
            editor.putFloat("currentTargetLat", (float) mCurrentTarget.latitude);
            editor.putFloat("currentTargetLng", (float) mCurrentTarget.longitude);
        }
        editor.apply();
    }

    @Override
    public void onConnected(Bundle bundle)
    {
        Log.d("on", "onConnected (GoogleApiClient) - isConnected ...............: " + mGoogleApiClient.isConnected());
        startLocationUpdates(mGoogleApiClient, true);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d("on", "onConnectedSuspended (GoogleApiClient) - isConnected ...............: " + mGoogleApiClient.isConnected());
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.w("on", "onConnectionFailed (GoogleApiClient)");
    }

    private void startLocationUpdates(GoogleApiClient googleApiClient, boolean bActive)
    {
        if ((googleApiClient == null) ||
            (!googleApiClient.isConnected()))
        {
            // sometimes this is called on startup, so only connect if ready
            // this is also called at OnConnected, so will kick things off then
            return;
        }
        LocationRequest locationRequest = new LocationRequest();

        if (bActive) {
            locationRequest.setInterval(1000)
                    .setFastestInterval(500)
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        }
        else
        // do a background location updates thing, w/geofence niceness to the slower of every 100m or every 1 minute
        // todo: test this
        {
            locationRequest.setFastestInterval(60000)
                    .setSmallestDisplacement(100)
                    .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
        }

        LocationServices.FusedLocationApi.requestLocationUpdates(
                googleApiClient, locationRequest, this);
    }

    private int getLteSignalAsGreenLevel()
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

    private void updateRecentCircles(Location location)
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

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        boolean bReturnMe = super.onTouchEvent(event);
        // turn off tracking
        mMapTrackCurrentLocation = false; // todo: Still doesn't work - this doesn't appear to be called - maybe kill this method, and try something else....
        Log.d("Track", "changed to false.");
        return bReturnMe;
    }
}