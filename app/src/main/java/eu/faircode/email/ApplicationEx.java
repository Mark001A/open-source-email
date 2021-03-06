package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2019 by Marcel Bokhorst (M66B)
*/

import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.DeadSystemException;
import android.os.Handler;
import android.os.RemoteException;
import android.webkit.CookieManager;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ApplicationEx extends Application {
    private Thread.UncaughtExceptionHandler prev = null;

    private static final List<String> DEFAULT_CHANNEL_NAMES = Collections.unmodifiableList(Arrays.asList(
            "service", "notification", "warning", "error"
    ));

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(getLocalizedContext(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logMemory("App create version=" + BuildConfig.VERSION_NAME);

        prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                if (ownFault(ex)) {
                    Log.e(ex);

                    if (BuildConfig.BETA_RELEASE ||
                            !Helper.isPlayStoreInstall(ApplicationEx.this))
                        writeCrashLog(ApplicationEx.this, ex);

                    if (prev != null)
                        prev.uncaughtException(thread, ex);
                } else {
                    Log.w(ex);
                    System.exit(1);
                }
            }
        });

        upgrade(this);

        createNotificationChannels();

        if (Helper.hasWebView(this))
            CookieManager.getInstance().setAcceptCookie(false);

        MessageHelper.setSystemProperties();
        ContactInfo.init(this, new Handler());
        Core.init(this);
    }

    @Override
    public void onTrimMemory(int level) {
        logMemory("Trim memory level=" + level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        logMemory("Low memory");
        super.onLowMemory();
    }

    private void logMemory(String message) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);
        int mb = Math.round(mi.availMem / 0x100000L);
        int perc = Math.round(mi.availMem / (float) mi.totalMem * 100.0f);
        Log.i(message + " " + mb + " MB" + " " + perc + " %");
    }

    static void upgrade(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int version = prefs.getInt("version", 468);
        if (version < BuildConfig.VERSION_CODE) {
            Log.i("Upgrading from " + version + " to " + BuildConfig.VERSION_CODE);

            SharedPreferences.Editor editor = prefs.edit();

            editor.remove("notify_trash");
            editor.remove("notify_archive");
            editor.remove("notify_reply");
            editor.remove("notify_flag");
            editor.remove("notify_seen");

            editor.putInt("version", BuildConfig.VERSION_CODE);
            editor.apply();
        }
    }

    static Context getLocalizedContext(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean english = prefs.getBoolean("english", false);

        if (english) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(Locale.US);
            return context.createConfigurationContext(config);
        } else
            return context;
    }

    private void createNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            NotificationChannel service = new NotificationChannel(
                    "service",
                    getString(R.string.channel_service),
                    NotificationManager.IMPORTANCE_MIN);
            service.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            service.setShowBadge(false);
            service.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(service);

            NotificationChannel notification = new NotificationChannel(
                    "notification",
                    getString(R.string.channel_notification),
                    NotificationManager.IMPORTANCE_HIGH);
            notification.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(notification);

            NotificationChannel warning = new NotificationChannel(
                    "warning",
                    getString(R.string.channel_warning),
                    NotificationManager.IMPORTANCE_HIGH);
            warning.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(warning);

            NotificationChannel error = new NotificationChannel(
                    "error",
                    getString(R.string.channel_error),
                    NotificationManager.IMPORTANCE_HIGH);
            error.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(error);

            NotificationChannelGroup group = new NotificationChannelGroup(
                    "contacts",
                    getString(R.string.channel_group_contacts));
            nm.createNotificationChannelGroup(group);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    static JSONArray channelsToJSON(Context context) throws JSONException {
        JSONArray jchannels = new JSONArray();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (NotificationChannel channel : nm.getNotificationChannels())
            if (!DEFAULT_CHANNEL_NAMES.contains(channel.getId())) {
                JSONObject jchannel = new JSONObject();

                jchannel.put("id", channel.getId());
                jchannel.put("group", channel.getGroup());
                jchannel.put("name", channel.getName());
                jchannel.put("description", channel.getDescription());

                jchannel.put("importance", channel.getImportance());
                jchannel.put("dnd", channel.canBypassDnd());
                jchannel.put("visibility", channel.getLockscreenVisibility());
                jchannel.put("badge", channel.canShowBadge());

                Uri sound = channel.getSound();
                if (sound != null)
                    jchannel.put("sound", sound.toString());
                // audio attributes

                jchannel.put("light", channel.shouldShowLights());
                // color

                jchannel.put("vibrate", channel.shouldVibrate());
                // pattern

                jchannels.put(jchannel);
            }

        return jchannels;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    static void channelsFromJSON(Context context, JSONArray jchannels) throws JSONException {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (int c = 0; c < jchannels.length(); c++) {
            JSONObject jchannel = (JSONObject) jchannels.get(c);

            String id = jchannel.getString("id");
            if (nm.getNotificationChannel(id) == null) {
                NotificationChannel channel = new NotificationChannel(
                        id,
                        jchannel.getString("name"),
                        jchannel.getInt("importance"));

                if (jchannel.has("group") && !jchannel.isNull("group"))
                    channel.setGroup(jchannel.getString("group"));
                else
                    channel.setGroup("contacts");

                if (jchannel.has("description") && !jchannel.isNull("description"))
                    channel.setDescription(jchannel.getString("description"));

                channel.setBypassDnd(jchannel.getBoolean("dnd"));
                channel.setLockscreenVisibility(jchannel.getInt("visibility"));
                channel.setShowBadge(jchannel.getBoolean("badge"));

                if (jchannel.has("sound") && !jchannel.isNull("sound")) {
                    Uri uri = Uri.parse(jchannel.getString("sound"));
                    Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
                    if (ringtone != null)
                        channel.setSound(uri, Notification.AUDIO_ATTRIBUTES_DEFAULT);
                }

                channel.enableLights(jchannel.getBoolean("light"));
                channel.enableVibration(jchannel.getBoolean("vibrate"));

                Log.i("Creating channel=" + channel);
                nm.createNotificationChannel(channel);
            }
        }
    }

    public boolean ownFault(Throwable ex) {
        if (ex instanceof OutOfMemoryError)
            return false;

        if (ex instanceof RemoteException)
            return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            if (ex instanceof RuntimeException && ex.getCause() instanceof DeadSystemException)
                return false;

        if (BuildConfig.BETA_RELEASE)
            return true;

        while (ex != null) {
            for (StackTraceElement ste : ex.getStackTrace())
                if (ste.getClassName().startsWith(getPackageName()))
                    return true;
            ex = ex.getCause();
        }

        return false;
    }

    static void writeCrashLog(Context context, Throwable ex) {
        File file = new File(context.getCacheDir(), "crash.log");
        Log.w("Writing exception to " + file);

        try (FileWriter out = new FileWriter(file, true)) {
            out.write(BuildConfig.VERSION_NAME + " " + new Date() + "\r\n");
            out.write(ex + "\r\n" + android.util.Log.getStackTraceString(ex) + "\r\n");
        } catch (IOException e) {
            Log.e(e);
        }
    }
}
