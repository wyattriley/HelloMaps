package com.wyattriley.hellomaps;

import android.graphics.Color;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Created by wyatt_000 on 11/8/2015.
 */
public class DataFilterByLatLng {

    private class LatLonGridPoint
    {
        // TODO: Make this flexible, upon instantiation, and do a 100, 1000, and 10,000 levels?
        final int GRID_SCALE = 10000;

        private int iLatGrid;
        private int iLonGrid;

        public LatLonGridPoint(Location location)
        {
            iLatGrid = (int)Math.round(GRID_SCALE * location.getLatitude());
            // 0.5 * is to stagger these every other row, so they make a hex pattern
            iLonGrid = (int)Math.round(GRID_SCALE * location.getLongitude() +
                                       0.5 * (iLatGrid % 2));
        }

        public LatLng getLatLng()
        {
            // 0.5 * is to stagger these every other row, so they make a hex pattern
            return (new LatLng(((double)iLatGrid)/GRID_SCALE,
                               ((double)iLonGrid -  0.5 * (iLatGrid % 2))/GRID_SCALE));
        }

        public int hashCode()
        {
            return (iLatGrid*GRID_SCALE*360 + iLonGrid);
        }
    }

    private class DataPoint {
        private int mNumSamples;
        private long mTotal;
        private LatLonGridPoint mLatLonGridPoint;

        public LatLng getLatLng()
        {
            return mLatLonGridPoint.getLatLng();
        }

        public DataPoint(Location location)
        {
            clearData();
            mLatLonGridPoint = new LatLonGridPoint(location);
        }

        public void clearData()
        {
            mTotal = 0;
            mNumSamples = 0;
        }

        public void addData(int iWeight, int iValue)
        {
            mNumSamples += iWeight;
            mTotal += (long)iWeight * (long)iValue;
        }

        public int getAve()
        {
            if (mNumSamples > 0)
            {
                return (int) (mTotal / mNumSamples);
            }
            else
                return 0;
        }
    }

    private Map<Integer, DataPoint> mMapSignalData;
    private LinkedList<Circle> mCirclesPlotted;

    public void AddData(Location location, int iWeight, int iValue)
    {
        LatLonGridPoint indexGrid = new LatLonGridPoint(location);
        Integer index = indexGrid.hashCode(); // todo fix this for 2^32 rollover

        if (mMapSignalData.containsKey(index))
        {
            mMapSignalData.get(index).addData(iWeight, iValue);
            Log.d("Grid", "Updating Point w/value" + iValue + " total weight " +
                    mMapSignalData.get(index).mNumSamples);
        }
        else
        {
            DataPoint dataPoint = new DataPoint(location);
            dataPoint.addData(iWeight, iValue);

            mMapSignalData.put(index, dataPoint);
            Log.d("Grid", "New Point w/value " + iValue + " at grid loc " +
                    indexGrid.iLatGrid + ", " + indexGrid.iLonGrid + ", hash: " + index);
        }
    }

    public void DrawCircles(GoogleMap googleMap)
    {
        // remove them all
        while (mCirclesPlotted.size() > 0) {
            mCirclesPlotted.remove().remove();
        }

        // and redraw them all - very inefficient...
        // todo: only remove & add those that changed?
        // todo: draw polygon rectangles, not circles
        // todo: remove lines on shape edge - and later add back a line at a significant change in sig strength?
        // todo: only draw those on screen
        // todo: only draw first 1000?
        // todo: different scales
        for (Map.Entry<Integer, DataPoint> entry : mMapSignalData.entrySet())
        {
            int iGreenLevel = entry.getValue().getAve();
            LatLng latLng = entry.getValue().getLatLng();
            mCirclesPlotted.add(
                    googleMap.addCircle(new CircleOptions() // todo - add rectangle of approp. size
                            .strokeWidth(2)
                            .strokeColor(Color.GRAY)
                            .center(latLng)
                            .radius(10)
                            .fillColor(Color.argb(64, 255 - iGreenLevel, iGreenLevel, 0))));
            if (mCirclesPlotted.size() == 1) {
                Log.d("Draw", "StrengthGrid " + mCirclesPlotted.size() + " entries at Lat: " + latLng.latitude);
            }
        }
        Log.d("Draw", "StrengthGrid " + mCirclesPlotted.size() + " entries ");
    }

    public DataFilterByLatLng()
    {
        mMapSignalData = new HashMap<>();
        mCirclesPlotted = new LinkedList<>();
    }
}
