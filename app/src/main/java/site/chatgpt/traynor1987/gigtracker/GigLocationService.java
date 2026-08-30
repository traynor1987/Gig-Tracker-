package site.chatgpt.traynor1987.gigtracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class GigLocationService extends Service implements LocationListener {
    static final String ACTION_START = "site.chatgpt.traynor1987.gigtracker.START";
    static final String ACTION_PAUSE = "site.chatgpt.traynor1987.gigtracker.PAUSE";
    static final String ACTION_RESUME = "site.chatgpt.traynor1987.gigtracker.RESUME";
    static final String ACTION_STOP = "site.chatgpt.traynor1987.gigtracker.STOP";
    static final String EXTRA_SESSION_ID = "session_id";

    private static final String CHANNEL_ID = "gig_tracker_gps";
    private static final int NOTIFICATION_ID = 4118;
    private static final String PREFS = "gig_tracker_native_service";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_PAUSED = "paused";
    private static final long UPDATE_INTERVAL_MS = 5_000L;
    private static final float UPDATE_DISTANCE_METRES = 5f;

    private final AtomicLong sequence = new AtomicLong();
    private LocationManager locationManager;
    private GpsSampleQueue sampleQueue;
    private SharedPreferences preferences;
    private String sessionId;
    private boolean paused;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sampleQueue = new GpsSampleQueue(this);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking();
            return START_NOT_STICKY;
        }

        String requestedId = intent == null ? null : cleanSessionId(intent.getStringExtra(EXTRA_SESSION_ID));
        if (requestedId == null) requestedId = cleanSessionId(preferences.getString(KEY_SESSION_ID, null));
        if (requestedId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        sessionId = requestedId;
        paused = ACTION_PAUSE.equals(action) || (action == null && preferences.getBoolean(KEY_PAUSED, false));
        preferences.edit().putString(KEY_SESSION_ID, sessionId).putBoolean(KEY_PAUSED, paused).apply();
        startForeground(NOTIFICATION_ID, buildNotification());

        if (paused) removeLocationUpdates();
        else requestLocationUpdates();
        NativeLocationBus.notifyPendingSamples();
        return START_STICKY;
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopTracking();
            return;
        }
        removeLocationUpdates();
        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_METRES, this);
                requested = true;
            }
        } catch (RuntimeException ignored) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_METRES, this);
                requested = true;
            }
        } catch (RuntimeException ignored) {}
        paused = false;
        preferences.edit().putBoolean(KEY_PAUSED, false).apply();
        if (!requested) updateNotification("Waiting for Android Location");
        else updateNotification("Tracking work mileage");
    }

    private void stopTracking() {
        removeLocationUpdates();
        preferences.edit().remove(KEY_SESSION_ID).remove(KEY_PAUSED).apply();
        sessionId = null;
        paused = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void removeLocationUpdates() {
        try {
            locationManager.removeUpdates(this);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onLocationChanged(Location location) {
        String activeSession = sessionId;
        if (paused || activeSession == null || !location.hasAccuracy()) return;
        try {
            JSONObject sample = new JSONObject()
                .put("providerTimestamp", location.getTime())
                .put("latitude", location.getLatitude())
                .put("longitude", location.getLongitude())
                .put("accuracyMetres", location.getAccuracy())
                .put("speedMetresPerSecond", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL)
                .put("headingDegrees", location.hasBearing() ? location.getBearing() : JSONObject.NULL)
                .put("source", "android_native");
            String id = String.format(Locale.ROOT, "%s:%d:%d", activeSession, location.getTime(), sequence.incrementAndGet());
            JSONObject envelope = new JSONObject()
                .put("id", id)
                .put("sessionId", activeSession)
                .put("sample", sample);
            sampleQueue.append(envelope);
            NativeLocationBus.notifyPendingSamples();
        } catch (Exception ignored) {}
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onDestroy() {
        removeLocationUpdates();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = paused ? "GPS paused — break driving is excluded" : "Tracking work mileage in the background";
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Gig Tracker GPS")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    private void updateNotification(String text) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Gig Tracker GPS")
            .setContentText(paused ? "GPS paused — break driving is excluded" : text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Gig Tracker GPS", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows while native gig-work mileage tracking is active");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private static String cleanSessionId(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() || clean.length() > 128 ? null : clean;
    }
}
