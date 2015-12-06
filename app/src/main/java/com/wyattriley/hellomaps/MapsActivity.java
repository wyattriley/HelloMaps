package com.wyattriley.hellomaps;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.Settings;
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
    private int mCountLocs;

    private DataFilterByLatLng mSignalData; // todo - test putting this outside the activity, so it persists on screen rotation (static class or ?)
    private boolean mSignalDataLoaded;
    private float mCurrentZoom; // for redraw on zoom
    private LatLng mCurrentTarget; // for redraw on pan

    final int MAX_RECENT_CIRCLES = 2;
    final String FILENAME = "saved_data_map_1";
    final int DEFAULT_ZOOM = 19;
    final String PREFS_MAP = "prefs_map";

    private OpenFileTask mOpenFileTask; // todo: consider using for faster cancellation on quick exit?

    private static MyLocationListener smLocationListener;
    // 11:25pm 12/2 - making this class static... ? OMG finally this works, no leaks in the test prog.
    // & later confirmed in the main prog.
    private static class MyLocationListener implements LocationListener
    {
        private MapsActivity mapsActivityRef;

        public void setRefActivity(MapsActivity mapsActivity)
        {
            mapsActivityRef = mapsActivity;
        }

        @Override
        public void onLocationChanged(Location location)
        {
            if (null != mapsActivityRef) {
                mapsActivityRef.onLocationChanged(location);
            }
        }
    }

    private DataFilterByLatLng readDataFromFile()
    {
        DataFilterByLatLng d = new DataFilterByLatLng();
        try
        {
            FileInputStream fis = openFileInput(FILENAME);
            d.readFromFile(fis);
            fis.close();
        }
        catch (java.io.IOException e)
        {
            Log.d("File", "Couldn't read data from " + FILENAME + " " + e.getMessage());
        }

        return d;
    }

    private void updateDataAndDrawPostLoad(DataFilterByLatLng d)
    {
        // todo someday: handle a merge from a read-in-struct, to one learned while awaiting file load
        //mSignalData.clearShapes();
        if (null == mSignalData)
        {
            Log.i("null", "mSignalData null on the finish of loading, not loading it");
            return;
        }
        mSignalData.clearBitmap();
        mSignalData = d;
        Log.d("Memory", "mSignalData set on load in activity w/hash:" + this.hashCode());
        mSignalDataLoaded = true;
        if (mMap != null) // if it's ready
        {
            mSignalData.draw(mMap);
        }
    }

    private class OpenFileTask extends AsyncTask<Void, Void, DataFilterByLatLng>
    {
        protected DataFilterByLatLng doInBackground(Void... v)
        {
            return readDataFromFile();
        }

        protected void onPostExecute(DataFilterByLatLng dataFilterByLatLng)
        {
            updateDataAndDrawPostLoad(dataFilterByLatLng);
        }
    }

    /* 12/3, 9:47AM - not needed, in multiple tries open/clsoe fast, screen rotates/closes -
       no leaks appear to be caused by the async task form of this

    private void openFileNotTask()
    {
        updateDataAndDrawPostLoad(readDataFromFile());
    }
    */

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
        Log.d("Memory", "mSignalData new'ed on create in activity w/hash:" + this.hashCode());

        // todo 1: add a text line (center top?) for status esp. of these async tasks
        mOpenFileTask =
                new OpenFileTask();
        mOpenFileTask.execute(); // load mSignalData, in other thread, to avoid delay

        //openFileNotTask();

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
            public void onCameraChange(CameraPosition cameraPosition) {
                // check if changed, and if so, save to check next time & redraw
                if ((cameraPosition.zoom != mCurrentZoom) ||
                        (cameraPosition.target != mCurrentTarget)) {

                    mCurrentZoom = cameraPosition.zoom;
                    mCurrentTarget = cameraPosition.target;
                    //mSignalData.drawShapes(mMap);
                    if (null == mSignalData)
                    {
                        Log.e("null", "onCameraChange couldn't draw a null mSignalData");
                        return; // todo 2: debug why I hit this (on restoring a long sleeping app) on 12/3 12:20am
                    }
                    mSignalData.draw(mMap);
                }

            }
        });

        mMap.setOnMyLocationButtonClickListener(new GoogleMap.OnMyLocationButtonClickListener() {
            @Override
            public boolean onMyLocationButtonClick() {
                // toggle tracking mode
                mMapTrackCurrentLocation = !mMapTrackCurrentLocation;
                Log.d("Track", "changed to " + mMapTrackCurrentLocation);
                return false;
            }
        });
        mSignalData.draw(mMap);
    }

    public void onLocationChanged(Location location) {
        Log.d("on", "onLocationChanged");

        if (mMap == null) // not ready yet
        {
            return;
        }

        LatLng myLatLng = new LatLng(location.getLatitude(), location.getLongitude());

        updateRecentCircles(location);
        if (mMapTrackCurrentLocation)
        {
         mMap.animateCamera(CameraUpdateFactory.newLatLng(myLatLng));
        }

        if (mSignalData == null)
        {
            Log.w("Memory", "Prevented adding data to null mSignalData in activity w/hash: " + this.hashCode() +
                            "Apparently happens after onDestroy, before the activity actually dies, when that activity still" +
                    "gets hit with an incoming onLocationChanged.");
            /* ViewRootImpl error in here typically hits around the same time:
             http://stackoverflow.com/questions/18028666/senduseractionevent-is-null
             suggests this is due to a particular type of SS devices (of which mine is one) that keep the old activity
             alive a bit too long.
             So even though this is only set to null, in onDestroy
                and it is cleanly new'ed in onCreate
                it's apparently possible for an onLocationChanged to be digested in between

            If so, this is the right thing to do, as it's a useless time to be updating the mSignalData...
            */

            return;
        }

        // TODO: FIX TABS/INDENTS
        if (Settings.Global.getInt(
                getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            Log.d("Telephony", "In airplane mode - not recording Lte Signal");
            return;  // todo 3 test this
        }


        int iGreenLevel = getLteSignalAsGreenLevel();
         // todo: track and map associated-Wifi signal strength too, toggling between the two per menu

        if (mSignalData.AddData(location, iGreenLevel))
        {
            // some data was added
            //todo: optimize this better than slowing it - e.g. do most work in an async task - and/or use tiles to update just the middle changed part...
            mCountLocs++;
            if (mCountLocs %5 == 0) {
                mSignalData.draw(mMap);
            }

            //mSignalData.drawShapes(mMap, location);

            mCircleQRecentSignal.add(
                    mMap.addCircle(new CircleOptions()
                            .strokeWidth(2)
                            .strokeColor(Color.GRAY)
                            .center(myLatLng)
                            .radius(location.getAccuracy())
                            .fillColor(DataFilterByLatLng.colorFromGreenLevel(iGreenLevel, true))));

            if (mCircleQRecentSignal.size() > MAX_RECENT_CIRCLES)
            {
                mCircleQRecentSignal.remove().remove();
            }
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

        startLocationUpdates(mGoogleApiClient, false); // slow updates

        if (mSignalDataLoaded) // only write out, if data has been loaded from file - to avoid losing data on a write before the async read has completed
        {
            long lFileSizeBytes = 0;
            // todo 6: make a function out of this block
            // Todo: figure out how to do this in a different thread - like the read, but avoiding a collision with the read...
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
                    Log.e("File", "Couldn't rename file from-to-to on write. From: "
                                  + from.getName() + " To: " + to.getName());
                }
                lFileSizeBytes = to.length();

            } catch (java.io.IOException e) {
                Log.w("File", "Couldn't save data to " + FILENAME + " " + e.getMessage());
            }

            try {
                // write to to external storage
                // todo 7: check folder existence first
                // todo: only write on menu command
                File external = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "MapMySignal1_backup");
                if (!external.exists() || // todo 8 - test: only write on file size ~10% greater
                     external.length() < (long)((double)lFileSizeBytes * 0.9)) {
                    FileOutputStream fosExternal = new FileOutputStream(external);
                    mSignalData.writeToFile(fosExternal);
                    fosExternal.close();
                }
                else {
                    Log.d("File", "skipping external write"); // todo - more logging of files size to test
                }
            }
                catch (java.io.IOException e) {
                    Log.w("File", "Couldn't save data to external DCIM backup " + e.getMessage());
                }

        }
        else
        {
            Log.w("File",
                    "Still loading onPause, so not written - and may cause Activity memory leak");
        }
    }

    protected void onStop()
    {
        super.onStop();
        Log.d("on", "onStop fired ..............");

        mGoogleApiClient.disconnect();
    }

    protected void onDestroy()
    {
        super.onDestroy();
        Log.d("on", "onDestroy fired ..............");

        // Save settings
        SharedPreferences settings = getSharedPreferences(PREFS_MAP, 0);
        SharedPreferences.Editor editor = settings.edit();
        // todo 9: this whole thing shouldn't be needed as the maps activity is supposed to do this
        // itself - just need to not move to 'here' simply on screen rotate?
        editor.putFloat("currentZoom", mCurrentZoom);
        if (mCurrentTarget != null) {
            editor.putFloat("currentTargetLat", (float) mCurrentTarget.latitude);
            editor.putFloat("currentTargetLng", (float) mCurrentTarget.longitude);
        }
        editor.apply();

        // todo 10: try removing much of the below if it 's not needed w.r.t. activity leaks
        if(mGoogleApiClient != null) {
            mGoogleApiClient.unregisterConnectionCallbacks(this);
            mGoogleApiClient.unregisterConnectionFailedListener(this);
            if (mGoogleApiClient.isConnected())
            {
                LocationServices.FusedLocationApi.removeLocationUpdates(
                        mGoogleApiClient, this);
                Log.d("on", "onDestroy - mGoogleApiClient still connected");
            }
        }

        // clean up map data pointer, and then map
        // ? skipping this because the whole activity - of which these are members, is about to be destoryed
        if (mSignalData != null)
        {
            mSignalData.clearBitmap();
            mSignalData = null;
            Log.d("Memory", "mSignalData nulled on destroy in activity w/hash:" + this.hashCode());
        }
        else
        {
            Log.w("on", "onDestroy mSignalData unexpectedly Null");
        }
        mCircleQRecentSignal.clear(); // deleting the references to objects to ensure the map can be killed w/o leaking

        // todo 11: try unwinding some of this, just to see if it's needed. w.r.t. activity fragment view leakage
        if (mMap != null)
        {
            Log.d("on", "onDestory Map MyLocation disabled, and cleared");
            // killed this one known source of leaks, but it is still leaking...
            // https://code.google.com/p/gmaps-api-issues/issues/detail?id=8111
            mMap.setMyLocationEnabled(false);
            mMap.clear();
        }



        //mOpenFileTask.cancel(true); // todo 1: check if I need to wait for this to be canceled?
    }

    @Override
    public void onConnected(Bundle bundle) {
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
        if (null == MapsActivity.smLocationListener)
            MapsActivity.smLocationListener = new MapsActivity.MyLocationListener();
        MapsActivity.smLocationListener.setRefActivity(this);

        LocationRequest locationRequest = new LocationRequest();
        if (bActive) {
            locationRequest.setInterval(1000)
                    .setFastestInterval(500)
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        }
        else
        // do a background location updates thing, w/geofence niceness to the slower of every 100m or every 1 minute
        // todo: test this - and probably set up in a service as this won't work much of the time??
        // 12/4, Noon: confirmed note working even after a simple OnStop()
        {
            // for test purposes, try this medium speed
            locationRequest.setInterval(3000).setFastestInterval(2000)
                    .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
            /*
            locationRequest.setFastestInterval(60000)
                    .setSmallestDisplacement(100)
                    .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
                    */
        }
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, locationRequest, MapsActivity.smLocationListener);
        Log.d("locReq", "started Loc. Req.");
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
        mMapTrackCurrentLocation = false; // todo 12: Still doesn't work - this doesn't appear to be called - maybe kill this method, and try something else....
        Log.d("Track", "changed to false.");
        return bReturnMe;
    }
}