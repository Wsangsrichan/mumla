/*
 * Copyright (C) 2024 Wsangsrichan
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

package se.lublin.mumla.service;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import se.lublin.mumla.Settings;

/**
 * Manages GPS location tracking and sends position updates to a Traccar server
 * using the OsmAnd protocol over HTTP.
 */
public class GpsTracker implements LocationListener {
    private static final String TAG = GpsTracker.class.getName();

    private final Context mContext;
    private final Settings mSettings;
    private LocationManager mLocationManager;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private boolean mTracking = false;

    public GpsTracker(Context context, Settings settings) {
        mContext = context.getApplicationContext();
        mSettings = settings;
    }

    public void start() {
        if (mTracking) return;
        if (!mSettings.isGpsTrackingEnabled()) return;

        String url = mSettings.getTraccarUrl();
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "Traccar URL not configured, GPS tracking not started");
            return;
        }

        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        if (mLocationManager == null) {
            Log.e(TAG, "LocationManager not available");
            return;
        }

        int interval = mSettings.getGpsUpdateInterval() * 1000;
        int minDistance = mSettings.getGpsMinDistance();

        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, interval, minDistance, this,
                    Looper.getMainLooper());
            mTracking = true;
            Log.i(TAG, "GPS tracking started (interval=" + interval + "ms, minDistance=" + minDistance + "m)");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }

        // Also try network provider for faster initial fix
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, interval, minDistance, this,
                    Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Network provider not available", e);
        }
    }

    public void stop() {
        if (!mTracking) return;

        if (mLocationManager != null) {
            mLocationManager.removeUpdates(this);
        }
        mTracking = false;
        Log.i(TAG, "GPS tracking stopped");
    }

    public boolean isTracking() {
        return mTracking;
    }

    public void restartIfEnabled() {
        stop();
        start();
    }

    @Override
    public void onLocationChanged(Location location) {
        mExecutor.execute(() -> postLocation(location));
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    private void postLocation(Location location) {
        String traccarUrl = mSettings.getTraccarUrl();
        String deviceId = mSettings.getTraccarDeviceId();
        if (traccarUrl == null || traccarUrl.isEmpty()) return;

        try {
            String baseUrl = traccarUrl.endsWith("/") ? traccarUrl : traccarUrl + "/";
            StringBuilder params = new StringBuilder();
            params.append("id=").append(URLEncoder.encode(
                    deviceId != null && !deviceId.isEmpty() ? deviceId : "mumla", "UTF-8"));
            params.append("&lat=").append(location.getLatitude());
            params.append("&lon=").append(location.getLongitude());
            params.append("&timestamp=").append(location.getTime() / 1000);
            params.append("&speed=").append(location.getSpeed());
            params.append("&bearing=").append(location.getBearing());
            if (location.hasAltitude()) {
                params.append("&altitude=").append(location.getAltitude());
            }

            URL url = new URL(baseUrl + "?" + params.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mumla-GPS/1.0");

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Traccar POST: " + responseCode + " lat=" + location.getLatitude()
                    + " lon=" + location.getLongitude());
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Failed to post location to Traccar", e);
        }
    }
}
