package com.wyattriley.hellomaps;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
public class DataFilterByLatLng {

    public void clearShapes() {
        for(ShapeDrawnInfo shape : mShapesPlotted)
        {
            shape.mPolygon.remove();
        }
    }

    // todo: consider whether I need this
    public void clearBitmap() {
        if (mGroundOverlay != null) {
            mGroundOverlay.remove();
            mGroundOverlay = null;
        }
    }

    private class LatLonGridPoint
    {
        private double dGridScale = getGridScaleFromExponent(22); // 22 is default scale? not needed...  todo, reconsider & remove

        private int iLatGrid;
        private int iLonGrid;

        private double getGridScaleFromExponent(int iGridScaleExponent)
        {
            return (1 << iGridScaleExponent) / 360.0;
        }
        // same exponent as gmaps for size of screen, but for size of one grid cell
        public LatLonGridPoint(Location location, int iGridScaleExp)
        {
            dGridScale = getGridScaleFromExponent(iGridScaleExp);

            // 0.5 * is to stagger these every other column, so they make a hex pattern
            iLonGrid = (int)Math.round(dGridScale * location.getLongitude());
            iLatGrid = (int)Math.round(dGridScale * location.getLatitude()+
                    0.5 * (iLonGrid % 2));
        }

        // for on-file-load
        public LatLonGridPoint(int iLatGridIn, int iLonGridIn, int iGridScaleExp) {
            dGridScale = getGridScaleFromExponent(iGridScaleExp);
            iLatGrid = iLatGridIn;
            iLonGrid = iLonGridIn;
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

    private class DataPoint {
        private int mNumSamples;
        private long mTotal; // todo: handle overflow/high numbers (cut in half when large enough)
        private LatLonGridPoint mLatLonGridPoint; // todo: recall and doc. why this is needed when we have a map of these

        // for on-file-load
        public DataPoint(int iNumSamples, long lTotal, LatLonGridPoint latLonGridPoint) {
            mNumSamples = iNumSamples;
            mTotal= lTotal;
            mLatLonGridPoint = latLonGridPoint;
        }

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
    private HashSet<ShapeDrawnInfo> mShapesPlotted;

    GroundOverlay mGroundOverlay;

    final int MAX_SHAPES_TO_PLOT = 400;

    // todo: make each grid it's own class?
    final int[] maiGridScaleExp = { 9, 12, 14, 16, 17, 18, 19, 20, 21, 22 };
    final int NUM_GRID_SCALES = 10;



    private List<Map<LatLonGridPoint, DataPoint>> mListMapSignalData;

    /*
       File format:
       - Version, int, 1
       - Number of Grids, int, [10]
         - Grid Scale, int, [9,...22]
         - Num Points (in this grid), int, ?
            - LatIndex, int
            - LonIndex, int
            - NumSamples, int
            - Total, long

       todo: make file writing it's own class?  and/or in it's own thread, w/thread safety
     */

    public void writeToFile(FileOutputStream fos)
    {
        try {
            DataOutputStream dos = new DataOutputStream(fos);
            dos.write(1); //version
            dos.write(NUM_GRID_SCALES);
            for(int iScale = 0; iScale < NUM_GRID_SCALES; iScale++)
            {
                dos.write(maiGridScaleExp[iScale]);
                Map<LatLonGridPoint, DataPoint> mapDataPoints = mListMapSignalData.get(iScale);
                dos.writeInt(mapDataPoints.size());
                int iCountDataPointsWritten = 0;
                for (Map.Entry<LatLonGridPoint, DataPoint> entry : mapDataPoints.entrySet())
                {
                    dos.writeInt(entry.getKey().iLatGrid);
                    dos.writeInt(entry.getKey().iLonGrid);
                    dos.writeInt(entry.getValue().mNumSamples);
                    dos.writeLong(entry.getValue().mTotal);
                    iCountDataPointsWritten++;
                }
                if (iCountDataPointsWritten != mapDataPoints.size())
                {
                    Log.e("File", "Corrupt Write - unexpected count of points written " +
                                  iCountDataPointsWritten + " vs size written " +
                                  mapDataPoints.size());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // read in file - checking version # and grid scales match expectations
    public void readFromFile(FileInputStream fis)
    {
        try {
            DataInputStream dis = new DataInputStream(fis);
            int iVersion = dis.read();
            if (iVersion != 1)
            {
                Log.e("File", "Read - Version not handled " + iVersion);
                return;
            }
            int iNumGridScales = dis.read();
            if (iNumGridScales != NUM_GRID_SCALES)
            {
                Log.e("File", "Read - Num grid scales not handled " + iNumGridScales);
                return;
            }
            for(int iScale = 0; iScale < iNumGridScales; iScale++)
            {
                int iGridScaleExp = dis.read();
                if (iGridScaleExp != maiGridScaleExp[iScale])
                {
                    Log.e("File", "Read - Grid scales not expected " + iGridScaleExp);
                    return;
                }
                Map<LatLonGridPoint, DataPoint> mapDataPoints = mListMapSignalData.get(iScale);
                mapDataPoints.clear();
                int iSize = dis.readInt();
                for(int iDataPoint = 0; iDataPoint < iSize; iDataPoint++)
                {
                    LatLonGridPoint latLonGridPoint =
                            new LatLonGridPoint(dis.readInt(), dis.readInt(), iGridScaleExp);
                    DataPoint dataPoint =
                            new DataPoint(dis.readInt(), dis.readLong(), latLonGridPoint);
                    mapDataPoints.put(latLonGridPoint, dataPoint);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public boolean AddData(Location location, int iValue)
    {
        boolean boolDataAdded = false;

        for (int iScale = 0; iScale < NUM_GRID_SCALES; iScale++)
        {
            int iGridScale = maiGridScaleExp[iScale];
            Map<LatLonGridPoint, DataPoint> mapSignalData = mListMapSignalData.get(iScale);

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

            if (mapSignalData.containsKey(indexGrid))
            {
                mapSignalData.get(indexGrid).addData(iWeight, iValue);
                /*
                Log.d("Grid", "Updating Point w/value " + iValue + " total weight " +
                mapSignalData.get(index).mNumSamples);
                */
            }
            else
            {
                DataPoint dataPoint = new DataPoint(location, iGridScale);
                dataPoint.addData(iWeight, iValue);

                mapSignalData.put(indexGrid, dataPoint);
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

        // this should be done at file load - warn if not
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
            LatLonGridPoint latLonGridPoint = new LatLonGridPoint(location, maiGridScaleExp[iGridScaleIndex]);
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
            final int color = colorFromGreenLevel(iGreenLevel, true);

            boolean bFoundAndUpdated = false;
            for(ShapeDrawnInfo shape : mShapesPlotted) // todo: something more efficient that brute force search for shape
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

        // this should be done at file load - warn if not
        if (initShapesPlottedIfNeeded())
        {
            Log.w("ShapesInit", "Was Null, needed re-init, in drawShapes (location)");
        }

        int iScale = getScaleForCurrentCameraPosition(googleMap);
        Map<LatLonGridPoint, DataPoint> mapSignalData =
          mListMapSignalData.get(iScale);

        List<ShapeDrawnInfo> listShapesToRemove = new LinkedList<>();
        HashSet<LatLonGridPoint> latLonGridPointSetAdd = new HashSet<>();

        decideShapesToKeepAndNewShapes(latLngBounds, mapSignalData, mShapesPlotted,
                listShapesToRemove, latLonGridPointSetAdd);

        // only remove & add those that changed
        drawListedShapes(listShapesToRemove, latLonGridPointSetAdd, iScale,
                         mapSignalData, googleMap);

        drawBitmap(googleMap);
        // todo: figure out if I should refactor like this: LatLngDisplay latLngDisplay = new LatLngDisplay(mapSignalData, googleMap);
        // with two selectable sub-classes - the one for hexes, and the one for bmp display?
    }

    private class HexMaker
    {
        // todo: use for both LL & non-LL hexes?
        private LatLngBounds mLatLngBounds;
        private int mIXPixels;
        private int mIYPixels;
        private double dScaleLonToXpix;
        private double dScaleLatToYpix;

        public HexMaker(LatLngBounds latLngBounds, int iXPix, int iYPix)
        {
            mLatLngBounds = latLngBounds;
            mIXPixels = iXPix;
            mIYPixels = iYPix;
            dScaleLonToXpix = mIXPixels / (latLngBounds.northeast.longitude - latLngBounds.southwest.longitude);
            dScaleLatToYpix = mIYPixels / (latLngBounds.northeast.latitude - latLngBounds.southwest.latitude);
        }

        private float scaleLonToXPix(double dLon)
        {
            float fReturnMe = (float)((dLon - mLatLngBounds.southwest.longitude) * dScaleLonToXpix);
            return Math.min(Math.max(fReturnMe, 0), mIXPixels);
        }

        private float scaleLatToYPix(double dLat)
        {
            float fReturnMe = (float)((mLatLngBounds.northeast.latitude - dLat) * dScaleLatToYpix);
            return Math.min(Math.max(fReturnMe, 0), mIYPixels);
        }

        public Path makePath(LatLonGridPoint mLatLonGridPoint)
        {
            List<LatLng> listLatLng = mLatLonGridPoint.getLatLngPoly();

            Path path = new Path();
            int iEnd = listLatLng.size() - 1;
            path.moveTo(scaleLonToXPix(listLatLng.get(iEnd).longitude),
                    scaleLatToYPix(listLatLng.get(iEnd).latitude));
            for (LatLng latLng : listLatLng)
            {
                path.lineTo(scaleLonToXPix(latLng.longitude),
                            scaleLatToYPix(latLng.latitude));
            }
            return path;
        }
    }

    public void drawBitmap(GoogleMap gMap) {
                 /* todo - complete & then iterate on multiple levels of zoom, no-transparency, the following to draw to a canvas/bitmap, then put the bitmap on the mMap
           todo - start with just a simple drawing, of the hexes at one level of zoom - trying to repro existing behavior, then draw two layers, then draw
                  multiple layers, including returning refined versions, from another thread to avoid slowing things down...  and finally, update with a
                  draw efficiency thing that simply updates the bitmap... */
        if (null == gMap)
        {
            Log.e("draw", "gMap unexpectedly null in drawBitmap - skipping draw");
            return;
        }


        final int iCanvasSize = 1024;
        Bitmap b = Bitmap.createBitmap(iCanvasSize, iCanvasSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        LatLngBounds latLngBoundsLarge =
                scaleLatLngBounds(gMap.getProjection().getVisibleRegion().latLngBounds, 1.2F);

        int iScaleIndexNominal = getScaleForCurrentCameraPosition(gMap);
        int iScaleIndexMin = Math.max(iScaleIndexNominal - 1, 0);
        // todo: consider stopping this at 3 if too detailed/slow to draw?
        int iScaleIndexMax = Math.min(iScaleIndexNominal + 4, NUM_GRID_SCALES);

        for (int iScaleIndex = iScaleIndexMin; iScaleIndex < iScaleIndexMax; iScaleIndex++) {
            boolean bTransparent = iScaleIndex < iScaleIndexNominal;
            Map<LatLonGridPoint, DataPoint> mapSignalData =
                    mListMapSignalData.get(iScaleIndex);

            HexMaker hexMaker = new HexMaker(latLngBoundsLarge, iCanvasSize, iCanvasSize);

            for (Map.Entry<LatLonGridPoint, DataPoint> entry : mapSignalData.entrySet()) {
                if (latLngBoundsLarge.contains(entry.getValue().getLatLng())) {
                    Path path = hexMaker.makePath(entry.getValue().mLatLonGridPoint);
                    paint.setColor(colorFromGreenLevel(entry.getValue().getAve(), bTransparent));
                    canvas.drawPath(path, paint);
                }
            }
        }

        // todo: apparently the following line is slow, so perhaps using tile overlays makes more sense
        BitmapDescriptor image = BitmapDescriptorFactory.fromBitmap(b);

        if (null != mGroundOverlay)
        {
            //mGroundOverlay.remove();
            mGroundOverlay.setPositionFromBounds(latLngBoundsLarge);
            mGroundOverlay.setImage(image); // todo: figure out why this line crashes sometimes - maybe clearbitmap related?
            // and/or related to the location changed response that comes in for previous activity?
        }
        else {
            mGroundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(image)
                    .positionFromBounds(latLngBoundsLarge)
                    .transparency(0.5f));
        }
    }

    private void drawListedShapes( List<ShapeDrawnInfo> listShapesToRemove,
                                   HashSet<LatLonGridPoint> latLonGridPointSetAdd,
                                   int iGridScaleIndex,
                                   Map<LatLonGridPoint, DataPoint> mapSignalData,
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
            final DataPoint dataPoint = mapSignalData.get(latLonGridPoint);
            final int iGreenLevel = dataPoint.getAve();

            ShapeDrawnInfo shapeDrawnInfo = new ShapeDrawnInfo();
            shapeDrawnInfo.mPolygon = googleMap.addPolygon(new PolygonOptions()
                    .strokeWidth(0)
                    .strokeColor(Color.GRAY)
                    .addAll(dataPoint.getLatLngPoly())
                    .fillColor(colorFromGreenLevel(iGreenLevel, true)));
            shapeDrawnInfo.mLatLonGridPoint = latLonGridPoint;
            mShapesPlotted.add(shapeDrawnInfo);

            if (mShapesPlotted.size() > MAX_SHAPES_TO_PLOT)
            {
                Log.d("Draw", "Too many shapes in visible region, stopping drawing");
                break; // for
            }
        }


    }

    private int getScaleForCurrentCameraPosition(GoogleMap googleMap)
    {
        // select which scale to draw from  zoom 18 or smaller -> 22, 17 -> 21, etc.
        int iScale = 0;
        float zoom = googleMap.getCameraPosition().zoom;
        while ((iScale < NUM_GRID_SCALES - 1) &&
                (maiGridScaleExp[iScale] - zoom < 3)) // while not enough will show, zoom in
        {
            iScale++;
        }
        return iScale;
    }

    private LatLngBounds scaleLatLngBounds(LatLngBounds latLngBounds, float fScale) // todo: reuse in the bitmap thing
    {
        LatLngBounds llbReturnMe = latLngBounds;
        if (fScale < 0.1)
            fScale = 0.1f;
        else if (fScale > 10.0)
            fScale = 10.0f;

        // max to sort-of avoid rollover at worlds edge - todo: make this better at world's edge
        double latRange = Math.max(latLngBounds.northeast.latitude - latLngBounds.southwest.latitude, 0.0);
        double lonRange = Math.max(latLngBounds.northeast.longitude - latLngBounds.southwest.longitude, 0.0);
        LatLng latLngDoubleNe = new LatLng(latLngBounds.northeast.latitude + latRange/2.0 * (fScale-1), // -1 pulls back to center
                latLngBounds.northeast.longitude + lonRange/2.0 * (fScale-1));
        LatLng latLngDoubleSw = new LatLng(latLngBounds.southwest.latitude - latRange/2.0 * (fScale-1),
                latLngBounds.southwest.longitude - lonRange/2.0 * (fScale-1));

        llbReturnMe = llbReturnMe.including(latLngDoubleNe);
        llbReturnMe = llbReturnMe.including(latLngDoubleSw);
        return llbReturnMe;
    }

    private void decideShapesToKeepAndNewShapes(LatLngBounds latLngBounds,
                                                Map<LatLonGridPoint, DataPoint> mapSignalData,
                                                HashSet<ShapeDrawnInfo> setShapesPlotted,
                                                List<ShapeDrawnInfo> listShapesToRemove,
                                                HashSet<LatLonGridPoint> latLonGridPointSetAdd)
    {
        // create optional bigger bounds in which to put things

        Queue<LatLonGridPoint> latLonGridPointListAddIfSpace = new LinkedList<>();
        LatLngBounds latLngBoundsLarge = scaleLatLngBounds(latLngBounds, 2);

        // first make list of all points to add
        for (Map.Entry<LatLonGridPoint, DataPoint> entry : mapSignalData.entrySet()) {
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

    public static int colorFromGreenLevel(int iGreenLevel, boolean bTransparent)
    {
        // 00FF00 at 255
        // C0FF00 at 192
        // FFFF00 at 128
        // FF0000 at 0
        int alpha = 128;
        if (!bTransparent)
        {
            alpha = 255;
        }
        if (iGreenLevel >= 192 &&  iGreenLevel <= 255)
        {
            // Red: 192->192, 255->0
            return Color.argb(alpha, 192-(3*(iGreenLevel-192)), 255, 0);
        }
        else if (iGreenLevel >= 128 &&  iGreenLevel < 192)
        {
            // Red: 128->255, 191->191
            return Color.argb(alpha, (383-iGreenLevel), 255, 0);
        }
        else
        {
            return Color.argb(alpha, 255, iGreenLevel * 2, 0);
        }
    }
}
