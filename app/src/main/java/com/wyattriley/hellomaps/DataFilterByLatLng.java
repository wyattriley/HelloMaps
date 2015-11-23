package com.wyattriley.hellomaps;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
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
import java.util.Queue;

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
        public LatLonGridPoint(Location location, int iGridScaleExponent)
        {
            final int iScaleWorldMult = 1 << iGridScaleExponent;
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

        // override
        @Override
        public boolean equals(Object that)
        {
            return ((this.getClass() == that.getClass()) &&
                    (this.dGridScale == ((LatLonGridPoint)that).dGridScale) &&
                    (this.iLatGrid   == ((LatLonGridPoint)that).iLatGrid) &&
                    (this.iLonGrid   == ((LatLonGridPoint)that).iLonGrid));
        }

        @Override
        public int hashCode()
        {
            /* semi-random prime # roughly halfway through the int's
               so as to pull slightly differ lat's far apart and let a bunch of
               similar Lon's fit neatly between
             */
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
        public Polygon mPolygon;
        public LatLonGridPoint mLatLonGridPoint;
    }
    transient private HashSet<ShapeDrawnInfo> mShapesPlotted;

    final int MAX_SHAPES_TO_PLOT = 300;

    final int[] maiGridScales = { 9, 12, 14, 16, 17, 18, 19, 20, 21, 22 };
    final int NUM_GRID_SCALES = 10;

    private List<Map<LatLonGridPoint, DataPoint>> mListMapSignalData;

    public boolean AddData(Location location, int iValue)
    {
        boolean boolDataAdded = false;

        for (int iScale = 0; iScale < NUM_GRID_SCALES; iScale++)
        {
            int iGridScale = maiGridScales[iScale];
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

    public boolean initShapesPlottedIfNeeded()
    {
        if (mShapesPlotted == null)
        {
            mShapesPlotted = new HashSet<>();
            return true;
        }
        return false;
    }

    // only update the shape at this location
    public void drawShapes(GoogleMap googleMap, Location location)
    {
        // todo: write all warnings to a log that's visible in the menu

        // todo: have the live circles on by default, be menu selectable off

        // todo-test: have track me stick when clicking the center-on-me, and un-stick on pan

        // this should be done at deserializaiton - warn if not
        if (initShapesPlottedIfNeeded())
        {
            Log.w("ShapesInit", "Was Null, needed re-init, in drawShapes (location)");
        }

        // only draw if visible
        if (googleMap.getProjection().getVisibleRegion().latLngBounds.contains(
                new LatLng(location.getLatitude(), location.getLongitude())))
        {
            int iGridScaleIndex = getScaleForCurrentCameraPosition(googleMap);

            // todo improve the names of the various scales index or integer, ai, or not...
            LatLonGridPoint latLonGridPoint = new LatLonGridPoint(location, maiGridScales[iGridScaleIndex]);
            Map<LatLonGridPoint, DataPoint> mapSignalData =
                    mListMapSignalData.get(iGridScaleIndex);

            DataPoint dataPoint = mapSignalData.get(latLonGridPoint);
            if (dataPoint == null)
            {
                Log.d("draw",
                        "couldn't update current point due to dataPoint not found. " +
                                "latLongGridPoint: " + latLonGridPoint.iLatGrid + " "
                                + latLonGridPoint.iLonGrid);
                return;
            }

            final int iGreenLevel = mapSignalData.get(latLonGridPoint).getAve();
            final int color = colorFromGreenLevel(iGreenLevel);

            boolean bFoundAndUpdated = false;
            for(ShapeDrawnInfo shape : mShapesPlotted)
            {
                if (shape.mLatLonGridPoint.equals(latLonGridPoint))
                {
                    shape.mPolygon.setFillColor(color);
                    bFoundAndUpdated = true;
                    break;
                }
                /*
                else
                {
                    Log.d("draw detail", latLonGridPoint.iLatGrid + " " + shape.latLonGridPoint.iLatGrid);
                    Log.d("draw detail", latLonGridPoint.iLonGrid + " " + shape.latLonGridPoint.iLonGrid);
                    Log.d("draw detail", latLonGridPoint.dGridScale + " " + shape.latLonGridPoint.dGridScale);
                }
                */
            }

            if (!bFoundAndUpdated)
            {
                // not found, need to draw it fresh
                List<ShapeDrawnInfo> listShapesToRemoveNone = new LinkedList<>();
                HashSet<LatLonGridPoint> latLonGridPointSetOfOneToAdd = new HashSet<>();
                latLonGridPointSetOfOneToAdd.add(latLonGridPoint);

                drawListedShapes(listShapesToRemoveNone,
                        latLonGridPointSetOfOneToAdd,
                        iGridScaleIndex,
                        mapSignalData,
                        googleMap);
            }
        }
    }

    // update all the shapes that need it, on a restart, pan or zoom
    public void drawShapes(GoogleMap googleMap)
    {
        Log.d("Draw", "Start drawShapes....");

        LatLngBounds latLngBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;

        // this should be done at deserializaiton - warn if not
        if (initShapesPlottedIfNeeded())
        {
            Log.w("ShapesInit", "Was Null, needed re-init, in drawShapes (location)");
        }

        int iScale = getScaleForCurrentCameraPosition(googleMap);
        Map<LatLonGridPoint, DataPoint> mMapSignalData =
          mListMapSignalData.get(iScale);

        List<ShapeDrawnInfo> listShapesToRemove = new LinkedList<>();
        HashSet<LatLonGridPoint> latLonGridPointSetAdd = new HashSet<>();

        decideShapesToKeepAndNewShapes(latLngBounds, mMapSignalData, mShapesPlotted,
                                       listShapesToRemove, latLonGridPointSetAdd);

        // only remove & add those that changed
        drawListedShapes(listShapesToRemove, latLonGridPointSetAdd, iScale,
                         mMapSignalData, googleMap);
    }

    private void drawListedShapes( List<ShapeDrawnInfo> listShapesToRemove,
                                   HashSet<LatLonGridPoint> latLonGridPointSetAdd,
                                   int iGridScaleIndex,
                                   Map<LatLonGridPoint, DataPoint> mMapSignalData,
                                   GoogleMap googleMap)
    {
        Log.d("Draw", "StrengthGrid: removing " + listShapesToRemove.size() +
                " and adding " + latLonGridPointSetAdd.size() +
                " entries at scale index " + iGridScaleIndex);

        for (ShapeDrawnInfo shapeDrawnInfo : listShapesToRemove) {
            shapeDrawnInfo.mPolygon.remove();
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
            shapeDrawnInfo.mPolygon = googleMap.addPolygon(new PolygonOptions()
                    .strokeWidth(0)
                    .strokeColor(Color.GRAY)
                    .addAll(dataPoint.getLatLngPoly())
                    .fillColor(colorFromGreenLevel(iGreenLevel)));
            shapeDrawnInfo.mLatLonGridPoint = latLonGridPoint;
            mShapesPlotted.add(shapeDrawnInfo);

            if (mShapesPlotted.size() > MAX_SHAPES_TO_PLOT)
            {
                Log.d("Draw", "Too many shapes in visible region, stopping drawing");
                break; // for
            }
        }

        /* todo - complete & then iterate on multiple levels of zoom, no-transparency, the following to draw to a canvas/bitmap, then put the bitmap on the mMap
           todo - start with just a simple drawing, of the hexes at one level of zoom - trying to repro existing behavior, then draw two layers, then draw
                  multiple layers, including returning refined versions, from another thread to avoid slowing things down...  and finally, update with a
                  draw efficiency thing that simply updates the bitmap...
        Bitmap b = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorFromGreenLevel(iGreenLevel));
        Path path = new Path(); // todo draw the path
        canvas.drawPath(path, paint);

        BitmapDescriptor image = new BitmapDescriptor();
        LatLngBounds latLngBounds = new LatLngBounds();

        GroundOverlay groundOverlay = googleMap.addGroundOverlay(new GroundOverlayOptions()
                .image(image)
                .positionFromBounds(latLngBounds)
                .transparency(0.5f));
        */
    }

    private int getScaleForCurrentCameraPosition(GoogleMap googleMap)
    {
        // select which scale to draw from  zoom 18 or smaller -> 22, 17 -> 21, etc.
        int iScale = 0;
        float zoom = googleMap.getCameraPosition().zoom;
        while ((iScale < NUM_GRID_SCALES - 1) &&
                (maiGridScales[iScale] - zoom < 3)) // while not enough will show, zoom in
        {
            iScale++;
        }
        return iScale;
    }

    private void decideShapesToKeepAndNewShapes(LatLngBounds latLngBounds,
                                                Map<LatLonGridPoint, DataPoint> mMapSignalData,
                                                HashSet<ShapeDrawnInfo> setShapesPlotted,
                                                List<ShapeDrawnInfo> listShapesToRemove,
                                                HashSet<LatLonGridPoint> latLonGridPointSetAdd)
    {
        // create optional bigger bounds in which to put things
        final int iBoundsScale = 2;
        Queue<LatLonGridPoint> latLonGridPointListAddIfSpace = new LinkedList<>();
        LatLngBounds latLngBoundsLarge = latLngBounds;
        // max to avoid rollover at worlds edge
        double latRange = Math.max(latLngBounds.northeast.latitude - latLngBounds.southwest.latitude, 0.0);
        double lonRange = Math.max(latLngBounds.northeast.longitude - latLngBounds.southwest.longitude, 0.0);
        LatLng latLngDoubleNe = new LatLng(latLngBounds.northeast.latitude + latRange/2.0 * (iBoundsScale-1),
                                           latLngBounds.northeast.longitude + lonRange/2.0 * (iBoundsScale-1));
        LatLng latLngDoubleSw = new LatLng(latLngBounds.southwest.latitude - latRange/2.0 * (iBoundsScale-1),
                                           latLngBounds.southwest.longitude - lonRange/2.0 * (iBoundsScale-1));

        latLngBoundsLarge = latLngBoundsLarge.including(latLngDoubleNe);
        latLngBoundsLarge = latLngBoundsLarge.including(latLngDoubleSw);

        // first make list of all points to add
        for (Map.Entry<LatLonGridPoint, DataPoint> entry : mMapSignalData.entrySet()) {
            if (latLngBounds.contains(entry.getValue().getLatLng()))
            {
                // want to be on new screen - decide keep, or add
                latLonGridPointSetAdd.add(entry.getKey());
            }
            else if (latLngBoundsLarge.contains(entry.getValue().getLatLng()))
            {
                // want to be on new screen - decide keep, or add
                latLonGridPointListAddIfSpace.add(entry.getKey());
            }
        }
        // copy more if space
        while ((latLonGridPointSetAdd.size() < MAX_SHAPES_TO_PLOT) &&
                (latLonGridPointListAddIfSpace.size() > 0))
        {
            latLonGridPointSetAdd.add(latLonGridPointListAddIfSpace.remove());
        }

        // then check for matches, deleting those from those that need to be added & adding to remove list
        for (ShapeDrawnInfo shapeDrawnInfo : setShapesPlotted)
        {
            if (!latLonGridPointSetAdd.remove(shapeDrawnInfo.mLatLonGridPoint))
            {
                listShapesToRemove.add(shapeDrawnInfo);
            }
        }
    }

    public DataFilterByLatLng()
    {
        mListMapSignalData = new ArrayList<>();
        for (int i = 0; i < NUM_GRID_SCALES; i++)
        {
            mListMapSignalData.add(new HashMap<LatLonGridPoint, DataPoint>());
        }
        mShapesPlotted = new HashSet<>();
    }

    public static int colorFromGreenLevel(int iGreenLevel)
    {
        // 00FF00 at 255
        // 808000 at 192
        // FFFF00 at 128
        // FF0000 at 0
        if (iGreenLevel >= 192 &&  iGreenLevel <= 255)
        {
            return Color.argb(128, 511 - iGreenLevel * 2, 255 - (255-iGreenLevel)*2, 0);
        }
        else if (iGreenLevel >= 128 &&  iGreenLevel < 192)
        {
            return Color.argb(128, 511 - iGreenLevel * 2, 255 - (iGreenLevel-128)*2, 0);
        }
        else
        {
            return Color.argb(128, 255, iGreenLevel * 2, 0);
        }
    }
}
