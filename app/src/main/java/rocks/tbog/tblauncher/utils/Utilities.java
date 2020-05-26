package rocks.tbog.tblauncher.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.lang.ref.WeakReference;

import rocks.tbog.tblauncher.result.ResultViewHelper;
import rocks.tbog.tblauncher.ui.CutoutFactory;
import rocks.tbog.tblauncher.ui.ICutout;

public class Utilities {
    private final static int[] ON_SCREEN_POS = new int[2];
    private final static Rect ON_SCREEN_RECT = new Rect();

    // https://stackoverflow.com/questions/3035692/how-to-convert-a-drawable-to-a-bitmap
    @NonNull
    public static Bitmap drawableToBitmap(@Nullable Drawable drawable) {
        Bitmap bitmap;

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        if (drawable != null) {
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        } else {
            canvas.drawRGB(255, 255, 255);
        }
        return bitmap;
    }

    @NonNull
    public static ICutout getNotchCutout(Activity activity) {
        ICutout cutout;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            cutout = CutoutFactory.getForAndroidPie(activity);
        else
            cutout = CutoutFactory.getByManufacturer(activity, Build.MANUFACTURER);

        return cutout == null ? CutoutFactory.getNoCutout() : cutout;
    }

    public static void setIconAsync(@NonNull ImageView image, @NonNull GetDrawable callback) {
        new Utilities.AsyncSetDrawable(image) {
            @Override
            protected Drawable getDrawable(Context context) {
                return callback.getDrawable(context);
            }
        }.execute();
    }

    public static void setIntentSourceBounds(@NonNull Intent intent, @NonNull View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            v.getLocationOnScreen(ON_SCREEN_POS);
            ON_SCREEN_RECT.set(ON_SCREEN_POS[0], ON_SCREEN_POS[1], ON_SCREEN_POS[0] + v.getWidth(), ON_SCREEN_POS[1] + v.getHeight());
            intent.setSourceBounds(ON_SCREEN_RECT);
        }
    }

    @Nullable
    public static Rect getOnScreenRect(@Nullable View v)
    {
        if (v == null)
            return null;
        v.getLocationOnScreen(ON_SCREEN_POS);
        ON_SCREEN_RECT.set(ON_SCREEN_POS[0], ON_SCREEN_POS[1], ON_SCREEN_POS[0] + v.getWidth(), ON_SCREEN_POS[1] + v.getHeight());
        return ON_SCREEN_RECT;
    }

    public interface GetDrawable {
        @Nullable
        Drawable getDrawable(@NonNull Context context);
    }

    public static abstract class AsyncSetDrawable extends AsyncTask<Void, Void, Drawable> {
        final WeakReference<ImageView> weakImage;

        protected AsyncSetDrawable(@NonNull ImageView image) {
            super();
            if (image.getTag() instanceof ResultViewHelper.AsyncSetEntryDrawable)
                ((ResultViewHelper.AsyncSetEntryDrawable) image.getTag()).cancel(true);
            image.setTag(this);
            image.setImageResource(android.R.color.transparent);
            this.weakImage = new WeakReference<>(image);
        }

        @Override
        protected Drawable doInBackground(Void... voids) {
            ImageView image = weakImage.get();
            if (isCancelled() || image == null || image.getTag() != this) {
                weakImage.clear();
                return null;
            }

            Context ctx = image.getContext();
            return getDrawable(ctx);
        }

        @WorkerThread
        protected abstract Drawable getDrawable(Context context);

        @Override
        protected void onPostExecute(Drawable drawable) {
            ImageView image = weakImage.get();
            if (image == null || drawable == null) {
                weakImage.clear();
                return;
            }
            image.setImageDrawable(drawable);
            image.setTag(null);
        }
    }

}
