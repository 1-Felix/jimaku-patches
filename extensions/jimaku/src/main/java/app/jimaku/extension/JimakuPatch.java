package app.jimaku.extension;

import android.app.Activity;
import android.util.Log;

/**
 * Entry points the Jimaku patch calls from YouTube's code.
 */
@SuppressWarnings("unused")
public final class JimakuPatch {
    public static final String TAG = "Jimaku";

    private JimakuPatch() {
    }

    /**
     * Called at the start of YouTube's main activity {@code onCreate}.
     */
    public static void onMainActivityCreate(Activity activity) {
        try {
            Log.i(TAG, "Extension loaded in " + activity.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "onMainActivityCreate failed", t);
        }
    }
}
