package gq.ledo.android_utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import static gq.ledo.android_utils.BuildConfig.LOG_TAG;

/**
 * Helper class for accessing properties in the AndroidManifest
 */
public final class ManifestHelper {
    private static Boolean debugEnabled = null;

    private final static String METADATA_DEBUG = "gq.ledo.Debuggable";

    private Context context;

    public ManifestHelper(Context context) {
        this.context = context;
    }

    /**
     * Grabs the debug flag from the manifest.
     *
     * @return true if the debug flag is enabled
     */
    public boolean isDebugEnabled() {
        return (null == debugEnabled) ? debugEnabled = getMetaDataBoolean(METADATA_DEBUG) : debugEnabled;
    }

    public String getMetaDataString(String name) {
        PackageManager pm = context.getPackageManager();
        String value = null;

        try {
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            value = ai.metaData.getString(name);
        } catch (Exception e) {
            if (isDebugEnabled()) {
                Log.d(LOG_TAG, "Couldn't find config value: " + name);
            }
        }

        return value;
    }

    public Integer getMetaDataInteger(String name) {
        PackageManager pm = context.getPackageManager();
        Integer value = null;

        try {
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            value = ai.metaData.getInt(name);
        } catch (Exception e) {
            if (isDebugEnabled()) {
                Log.d(LOG_TAG, "Couldn't find config value: " + name);
            }
        }

        return value;
    }

    public Boolean getMetaDataBoolean(String name) {
        PackageManager pm = context.getPackageManager();
        Boolean value = false;

        try {
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            value = ai.metaData.getBoolean(name);
        } catch (Exception e) {
            Log.d(LOG_TAG, "Couldn't find config value: " + name);
        }

        return value;
    }
}