package com.wyattriley.hellomaps;

import android.graphics.Color;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
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
            // 0.5 * is to stagger these every other column, so they make a hex pattern
            iLonGrid = (int)Math.round(GRID_SCALE * location.getLongitude());
            iLatGrid = (int)Math.round(GRID_SCALE * location.getLatitude()+
                    0.5 * (iLonGrid % 2));
        }

        private double latGridAsDouble()
        {
            return (double)(iLatGrid -  0.5 * (iLonGrid % 2))/GRID_SCALE;
        }
        private double lonGridAsDouble()
        {
            return ((double)iLonGrid) / GRID_SCALE;
        }
        
        public LatLng getLatLng()
        {
            // 0.5 * is to stagger these every other row, so they make a hex pattern
            return (new LatLng(latGridAsDouble(),
                               lonGridAsDouble()));
        }

        public int hashCode()
        {
            return (iLatGrid*GRID_SCALE*360 + iLonGrid);
        }

        // hexagon
        public List<LatLng> getLatLngPoly()
        {
            List<LatLng> listLatLng = new LinkedList<>();
            final double dLatOffset = (1.0/GRID_SCALE) /2.0;
            final double dLonOffset = (1.0/GRID_SCALE) /3.0;
            
            // right to bottom
            listLatLng.add(new LatLng(latGridAsDouble(),
                                      lonGridAsDouble() + 2.0 * dLonOffset));
            listLatLng.add(new LatLng(latGridAsDouble() - 1.0 * dLatOffset,
                                      lonGridAsDouble() + 1.0 * dLonOffset));
            listLatLng.add(new LatLng(latGridAsDouble() - 1.0 * dLatOffset,
                                      lonGridAsDouble() - 1.0 * dLonOffset));
            // left toward top
            listLatLng.add(new LatLng(latGridAsDouble(),
                                      lonGridAsDouble() - 2.0 * dLonOffset));
            listLatLng.add(new LatLng(latGridAsDouble() + 1.0 * dLatOffset,
                                      lonGridAsDouble() - 1.0 * dLonOffset));
            listLatLng.add(new LatLng(latGridAsDouble() + 1.0 * dLatOffset,
                                      lonGridAsDouble() + 1.0 * dLonOffset));
            
            return listLatLng;
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
        public List<LatLng> getLatLngPoly()
        {
            return  mLatLonGridPoint.getLatLngPoly();
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
    private LinkedList<Polygon> mShapesPlotted;

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

    public void drawShapes(GoogleMap googleMap)
    {
        // remove them all
        while (mShapesPlotted.size() > 0) {
            mShapesPlotted.remove().remove();
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

            /*
            mCirclesPlotted.add(
                    googleMap.addCircle(new CircleOptions() // todo - add rectangle of approp. size
                            .strokeWidth(2)
                            .strokeColor(Color.GRAY)
                            .center(latLng)
                            .radius(10)
                            .fillColor(Color.argb(64, 255 - iGreenLevel, iGreenLevel, 0))));
            */
            mShapesPlotted.add(
                    googleMap.addPolygon(new PolygonOptions()
                            .strokeWidth(1)
                            .strokeColor(Color.GRAY)
                            .addAll(entry.getValue().getLatLngPoly())
                            .fillColor(Color.argb(64, 255 - iGreenLevel, iGreenLevel, 0))));
            
            // todo - debug why some aren't draw after rotate
            if (mShapesPlotted.size() <= 3) {
                Log.d("Draw", "StrengthGrid " + mShapesPlotted.size() +
                              " entries at Lat: " + latLng.latitude + " Lon: " + latLng.longitude);
            }
        }
        Log.d("Draw", "StrengthGrid " + mShapesPlotted.size() + " entries ");
    }

    public DataFilterByLatLng()
    {
        mMapSignalData = new HashMap<>();
        mShapesPlotted = new LinkedList<>();
    }
}
