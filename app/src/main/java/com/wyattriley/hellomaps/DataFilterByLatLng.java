package com.wyattriley.hellomaps;

import android.graphics.Color;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Created by wyatt on 11/8/2015.   This file implements a multi-scale 2D data grid, and hex output display
 */
public class DataFilterByLatLng implements Serializable {

    private class LatLonGridPoint implements Serializable
    {
        final int iScaleWorldMultDefault = 1 << 22;
        private double dGridScale = iScaleWorldMultDefault / 360.0;

        private int iLatGrid;
        private int iLonGrid;

        // same exponent as gmaps for size of screen, but for size of one grid cell
        public LatLonGridPoint(Location location, int iScaleExponent)
        {
            final int iScaleWorldMult = 1 << iScaleExponent;
            dGridScale = iScaleWorldMult / 360.0;

            // 0.5 * is to stagger these every other column, so they make a hex pattern
            iLonGrid = (int)Math.round(dGridScale * location.getLongitude());
            iLatGrid = (int)Math.round(dGridScale * location.getLatitude()+
                    0.5 * (iLonGrid % 2));
        }

        private double latGridAsDouble()
        {
            return (iLatGrid -  0.5 * (iLonGrid % 2)) / dGridScale;
        }
        private double lonGridAsDouble()
        {
            return ((double)iLonGrid) / dGridScale;
        }
        
        public LatLng getLatLng()
        {
            // 0.5 * is to stagger these every other row, so they make a hex pattern
            return (new LatLng(latGridAsDouble(),
                               lonGridAsDouble()));
        }

        /*
        public long hashForIndexing()
        {
            return ((long)(iLatGrid)) << 32 + iLonGrid;
        }
        */

        // override
        @Override
        public boolean equals(Object that)
        {
            return ((this.dGridScale == ((LatLonGridPoint)that).dGridScale) &&
                    (this.iLatGrid   == ((LatLonGridPoint)that).iLatGrid) &&
                    (this.iLonGrid   == ((LatLonGridPoint)that).iLonGrid));
        }

        @Override
        public int hashCode()
        {
            return ((142567 * iLatGrid) + iLonGrid);
        }



        // hexagon
        public List<LatLng> getLatLngPoly()
        { //todo: as this is always hex, speed up by making array
            List<LatLng> listLatLng = new LinkedList<>();
            final double dLatOffset = (1.0/dGridScale) /2.0;
            final double dLonOffset = (1.0/dGridScale) /3.0;
            
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

    private class DataPoint implements Serializable {
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

        public DataPoint(Location location, int iGridScale)
        {
            clearData();
            mLatLonGridPoint = new LatLonGridPoint(location, iGridScale);
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

    private class ShapeDrawnInfo
    {
        public Polygon polygon;
        public LatLonGridPoint latLonGridPoint;
    }
    transient private HashSet<ShapeDrawnInfo> mShapesPlotted;

    final int[] aiScales = { 16, 17, 18, 19, 20, 21, 22 };
    final int iNumScales = 7;

    private List<Map<LatLonGridPoint, DataPoint>> mListMapSignalData;

    public boolean AddData(Location location, int iValue)
    {
        boolean boolDataAdded = false;

        for (int iScale = 0; iScale < iNumScales; iScale++)
        {
            int iGridScale = aiScales[iScale];
            Map<LatLonGridPoint, DataPoint> mMapSignalData = mListMapSignalData.get(iScale);

            final double dApproxGridSize = 6000000 * 6.28 / (1 << iGridScale);

            //Log.d("AddData", "For scale " + iScale + " grid size " + dApproxGridSize);

            int iWeight = 1; // default is limited weight for moderate overlap with grid point

            if(location.getAccuracy() > dApproxGridSize * 3.0)
                continue; // todo - overlay some text on the google map for status of too high to update
            else if(location.getAccuracy() < dApproxGridSize / 2.0) // nice & tightly inside
                iWeight = 4;
            else if(location.getAccuracy() < dApproxGridSize )
                iWeight  = 2; // somewhat inside

            // todo clean up excess logging
            //Log.d("AddData", "Picked weight " + iWeight);
            boolDataAdded = true;

            LatLonGridPoint indexGrid = new LatLonGridPoint(location, iGridScale);
            //Long index = indexGrid.hashForIndexing();

            if (mMapSignalData.containsKey(indexGrid))
            {
                mMapSignalData.get(indexGrid).addData(iWeight, iValue);
                /*
                Log.d("Grid", "Updating Point w/value " + iValue + " total weight " +
                mMapSignalData.get(index).mNumSamples);
                */
            }
            else
            {
                DataPoint dataPoint = new DataPoint(location, iGridScale);
                dataPoint.addData(iWeight, iValue);

                mMapSignalData.put(indexGrid, dataPoint);
                Log.d("Grid", "New Point w/value " + iValue + " at grid loc " +
                        indexGrid.iLatGrid + ", " + indexGrid.iLonGrid + " hashcode: " + indexGrid );
            }
        }
        return boolDataAdded;
    }

    // only update the shape at this location

    // todo see if i can finish this when more awake
    public void drawShapes(GoogleMap googleMap, Location location)
    {
        // only draw if visible
        if (googleMap.getProjection().getVisibleRegion().latLngBounds.contains(
                new LatLng(location.getLatitude(), location.getLongitude())))
        {
            List<ShapeDrawnInfo> listShapesToRemove = new LinkedList<>();
            int iScale = getScaleForCurrentCameraPosition(googleMap);

            Map<LatLonGridPoint, DataPoint> mMapSignalData =
                    mListMapSignalData.get(getScaleForCurrentCameraPosition(googleMap));

            /*
            listShapesToRemove.add();
            aiScales[iScale];
            */

        }


    }

    private int getScaleForCurrentCameraPosition(GoogleMap googleMap)
    {
        // select which scale to draw from  zoom 18 or smaller -> 22, 17 -> 21, etc.
        int iScale = 0;
        float zoom = googleMap.getCameraPosition().zoom;
        while ((iScale < iNumScales - 1) &&
                (aiScales[iScale] - zoom < 3)) // while not enough will show, zoom in
        {
            iScale++;
        }
        return iScale;
    }

    // update all the shapes that need it, on a restart, pan or zoom
    public void drawShapes(GoogleMap googleMap)
    {
        // start
        Log.d("Draw", "Start drawShapes....");

        LatLngBounds latLngBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;

        // recreate list post deserialization
        if (mShapesPlotted == null) {
            mShapesPlotted = new HashSet<>();
        }

        int iScale = getScaleForCurrentCameraPosition(googleMap);
        Map<LatLonGridPoint, DataPoint> mMapSignalData =
          mListMapSignalData.get(iScale);


        HashSet<LatLonGridPoint> latLonGridPointSetAdd = new HashSet<>();
        List<ShapeDrawnInfo> listShapesToRemove = new LinkedList<>();

        decideShapesToKeepAndNewShapes(latLngBounds, mMapSignalData, mShapesPlotted,
                                       listShapesToRemove, latLonGridPointSetAdd);

        // and redraw them all - very inefficient...
        // todo: only remove & add those that changed?
        // todo: only draw first 300? - check if ok

        Log.d("Draw", "StrengthGrid: removing " + listShapesToRemove.size() +
                " and adding " + latLonGridPointSetAdd.size() +
                " entries at scale index " + iScale);

        for (ShapeDrawnInfo shapeDrawnInfo : listShapesToRemove) {
            shapeDrawnInfo.polygon.remove();
            if (!mShapesPlotted.remove(shapeDrawnInfo))
            {
                Log.e("Draw", "deleted a shape that didn't appear to be in the set of known shapes");
            }
        }

        for (LatLonGridPoint latLonGridPoint : latLonGridPointSetAdd)
        {
            final DataPoint dataPoint = mMapSignalData.get(latLonGridPoint);
            final int iGreenLevel = dataPoint.getAve();

            ShapeDrawnInfo shapeDrawnInfo = new ShapeDrawnInfo();
            shapeDrawnInfo.polygon = googleMap.addPolygon(new PolygonOptions()
                    .strokeWidth(0)
                    .strokeColor(Color.GRAY)
                    .addAll(dataPoint.getLatLngPoly())
                    .fillColor(Color.argb(64, 255 - iGreenLevel, iGreenLevel, 0)));
            shapeDrawnInfo.latLonGridPoint = latLonGridPoint;
            mShapesPlotted.add(shapeDrawnInfo);

            if (mShapesPlotted.size() > 350)
            {
                Log.d("Draw", "Too many shapes in visible region, stopping drawing");
                break; // for
            }
        }
    }

    private void decideShapesToKeepAndNewShapes(LatLngBounds latLngBounds,
                                                Map<LatLonGridPoint, DataPoint> mMapSignalData,
                                                HashSet<ShapeDrawnInfo> setShapesPlotted,
                                                List<ShapeDrawnInfo> listShapesToRemove,
                                                HashSet<LatLonGridPoint> latLonGridPointSetAdd)
    {
        // first make list of all points to add
        for (Map.Entry<LatLonGridPoint, DataPoint> entry : mMapSignalData.entrySet()) {
            if (latLngBounds.contains(entry.getValue().getLatLng())) {
                // want to be on new screen - decide keep, or add
                latLonGridPointSetAdd.add(entry.getKey());
            }
        }

        // then check for matches, deleting those from those that need to be added & adding to remove list
        for (ShapeDrawnInfo shapeDrawnInfo : setShapesPlotted)
        {
            if (!latLonGridPointSetAdd.remove(shapeDrawnInfo.latLonGridPoint))
            {
                listShapesToRemove.add(shapeDrawnInfo);
            }
        }


        // todo - also redraw the data-changed cells, on data change
    }

    public DataFilterByLatLng()
    {
        mListMapSignalData = new ArrayList<>();
        for (int i = 0; i < iNumScales; i++)
        {
            mListMapSignalData.add(new HashMap<LatLonGridPoint, DataPoint>());
        }
        mShapesPlotted = new HashSet<>();
    }
}
