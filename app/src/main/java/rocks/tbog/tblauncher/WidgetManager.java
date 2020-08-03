package rocks.tbog.tblauncher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;

import rocks.tbog.tblauncher.db.DBHelper;
import rocks.tbog.tblauncher.db.WidgetRecord;
import rocks.tbog.tblauncher.ui.LinearAdapter;
import rocks.tbog.tblauncher.ui.LinearAdapterPlus;
import rocks.tbog.tblauncher.ui.ListPopup;
import rocks.tbog.tblauncher.ui.WidgetLayout;
import rocks.tbog.tblauncher.ui.WidgetView;

public class WidgetManager {
    private static final String TAG = "Wdg";
    private AppWidgetManager mAppWidgetManager;
    private WidgetHost mAppWidgetHost;
    private WidgetLayout mLayout;
    private WidgetLayout.Handle mLastMoveType = WidgetLayout.Handle.MOVE_FREE;
    private WidgetLayout.Handle mLastResizeType = WidgetLayout.Handle.RESIZE_DIAGONAL;
    private final ArrayMap<Integer, WidgetRecord> mWidgets = new ArrayMap<>(0);
    private static final int APPWIDGET_HOST_ID = 1337;
    private static final int REQUEST_PICK_APPWIDGET = 101;
    private static final int REQUEST_CREATE_APPWIDGET = 102;

    /**
     * Registers the AppWidgetHost to listen for updates to any widgets this app has.
     */
    boolean start(Context context) {
        Context ctx = context.getApplicationContext();
        mAppWidgetManager = AppWidgetManager.getInstance(ctx);
        try {
            mAppWidgetHost = new WidgetHost(ctx);
            mAppWidgetHost.startListening();
        } catch (android.content.res.Resources.NotFoundException e) {
            Log.e(TAG, "startListening failed", e);
            // Widget app was just updated? See https://github.com/Neamar/KISS/issues/959
            mAppWidgetHost = null;
            return false;
        }
        return true;
    }

    void stop() {
        mAppWidgetHost.stopListening();
        mAppWidgetHost = null;
    }

    /**
     * Called on the creation of the activity.
     */
    public void onCreateActivity(Activity activity) {
        mLayout = activity.findViewById(R.id.widgetContainer);
        mLayout.setPageCount(LiveWallpaper.SCREEN_COUNT_HORIZONTAL, LiveWallpaper.SCREEN_COUNT_VERTICAL);
        restoreWidgets();
        // post the scroll event to happen after the measure and layout phase
        final LiveWallpaper lw = TBApplication.liveWallpaper(activity);
        mLayout.post(new Runnable() {
            @Override
            public void run() {
                if (mLayout.getMeasuredWidth() == 0) {
                    // layout did not happen yet, wait some more
                    mLayout.removeCallbacks(this);
                    mLayout.post(this);
                    return;
                }
                PointF offset = lw.getWallpaperOffset();
                WidgetManager.this.scroll(offset.x, offset.y);
            }
        });
    }

    private void restoreWidgets() {
        mLayout.removeAllViews();

        if (mAppWidgetHost == null) {
            Log.w(TAG, "`restoreWidgets` called prior to `startListening`");
            if (!start(mLayout.getContext())) {
                Log.w(TAG, "`start` failed, try `restoreWidgets` after 500ms");
                mLayout.postDelayed(this::restoreWidgets, 500);
                return;
            }
        }

        int[] appWidgetIds;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appWidgetIds = mAppWidgetHost.getAppWidgetIds();
        } else {
            appWidgetIds = new int[0];
        }

        {
            ArrayList<WidgetRecord> widgets = DBHelper.getWidgets(mLayout.getContext());
            mWidgets.clear();
            mWidgets.ensureCapacity(widgets.size());
            for (WidgetRecord record : widgets)
                mWidgets.put(record.appWidgetId, record);
        }

        // sync DB with AppWidgetHost
        for (int appWidgetId : appWidgetIds) {
            if (!mWidgets.containsKey(appWidgetId)) {
                // remove widget that has no info in DB
                removeWidget(appWidgetId);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ArrayList<Integer> toDelete = new ArrayList<>(0);
            for (WidgetRecord rec : mWidgets.values()) {
                boolean found = false;
                for (int appWidgetId : appWidgetIds)
                    if (appWidgetId == rec.appWidgetId) {
                        found = true;
                        break;
                    }
                if (!found) {
                    // remove widget from DB
                    toDelete.add(rec.appWidgetId);
                }
            }
            for (int appWidgetId : toDelete)
                removeWidget(appWidgetId);
        }

        // restore widgets
        for (WidgetRecord rec : mWidgets.values()) {
            restoreWidget(rec);
        }
    }

    private void restoreWidget(WidgetRecord rec) {
        final int appWidgetId = rec.appWidgetId;
        Context ctx = mLayout.getContext().getApplicationContext();
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        if (appWidgetInfo == null)
            return;
        AppWidgetHostView hostView = mAppWidgetHost.createView(ctx, appWidgetId, appWidgetInfo);

        addWidgetToLayout(hostView, appWidgetInfo, rec);
    }

    private void addWidgetToLayout(AppWidgetHostView hostView, AppWidgetProviderInfo appWidgetInfo, WidgetRecord rec) {
        ViewGroup.LayoutParams params = new LinearLayout.LayoutParams(rec.width, rec.height);
        hostView.setLayoutParams(params);
        hostView.setMinimumWidth(rec.width);
        hostView.setMinimumHeight(rec.height);

        hostView.setAppWidget(rec.appWidgetId, appWidgetInfo);
        hostView.updateAppWidgetSize(null, rec.width, rec.height, rec.width, rec.height);

        hostView.setOnLongClickListener(v -> {
            if (v instanceof WidgetView) {
                ListPopup menu = getConfigPopup((WidgetView) v);
                TBApplication.behaviour(v.getContext()).registerPopup(menu);
                menu.show(v, 0.f);
                return true;
            }
            return false;
        });

        mLayout.addView(hostView);
    }

    public boolean usingActivity(Activity activity) {
        return activity.findViewById(R.id.widgetContainer) == mLayout;
    }

    /**
     * Launches the menu to select the widget. The selected widget will be on
     * the result of the activity.
     */
    public void selectWidget(Activity activity) {
        int appWidgetId = this.mAppWidgetHost.allocateAppWidgetId();
        Intent pickIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        addEmptyData(pickIntent);
        activity.startActivityForResult(pickIntent, REQUEST_PICK_APPWIDGET);
    }

    /**
     * This avoids a bug in the com.android.settings.AppWidgetPickActivity,
     * which is used to select widgets. This just adds empty extras to the
     * intent, avoiding the bug.
     * <p>
     * See more: http://code.google.com/p/android/issues/detail?id=4272
     */
    private void addEmptyData(Intent pickIntent) {
        ArrayList<AppWidgetProviderInfo> customInfo = new ArrayList<>();
        pickIntent.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, customInfo);
        ArrayList<Bundle> customExtras = new ArrayList<>();
        pickIntent.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, customExtras);
    }

    /**
     * Checks if the widget needs any configuration. If it needs, launches the
     * configuration activity.
     */
    private void configureWidget(Activity activity, Intent data) {
        Bundle extras = data != null ? data.getExtras() : null;
        if (extras == null)
            return;
        int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);

//        Bundle opts = new Bundle();
//        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, maxWidth);
//        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight);
//        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth);
//        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight);

        boolean hasPermission = mAppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, appWidgetInfo.provider);
        if (!hasPermission) {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, appWidgetInfo.provider);
            activity.startActivityForResult(intent, REQUEST_PICK_APPWIDGET/*REQUEST_BIND*/);
        }

        if (appWidgetInfo.configure != null) {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
            intent.setComponent(appWidgetInfo.configure);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            activity.startActivityForResult(intent, REQUEST_CREATE_APPWIDGET);
        } else {
            createWidget(activity, data);
        }
    }

    /**
     * Creates the widget and adds it to our view layout.
     */
    public void createWidget(Activity activity, Intent data) {
        Bundle extras = data != null ? data.getExtras() : null;
        if (extras == null)
            return;
        int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        if (appWidgetInfo == null)
            return;
        AppWidgetHostView hostView = mAppWidgetHost.createView(activity.getApplicationContext(), appWidgetId, appWidgetInfo);

        WidgetRecord rec = new WidgetRecord();
        rec.appWidgetId = appWidgetId;
        rec.width = appWidgetInfo.minWidth;
        rec.height = appWidgetInfo.minHeight;

        DBHelper.addWidget(activity, rec);
        mWidgets.put(rec.appWidgetId, rec);

        addWidgetToLayout(hostView, appWidgetInfo, rec);
    }

    /**
     * Removes the widget displayed by this AppWidgetHostView.
     */
    public void removeWidget(AppWidgetHostView hostView) {
        final int appWidgetId = hostView.getAppWidgetId();
        mLayout.removeWidget(hostView);

        mAppWidgetHost.deleteAppWidgetId(appWidgetId);
        DBHelper.removeWidget(mLayout.getContext(), appWidgetId);
        mWidgets.remove(appWidgetId);
    }

    public void removeWidget(int appWidgetId) {
        mLayout.removeWidget(appWidgetId);

        mAppWidgetHost.deleteAppWidgetId(appWidgetId);
        DBHelper.removeWidget(mLayout.getContext(), appWidgetId);
        mWidgets.remove(appWidgetId);
    }

    /**
     * Called from the main activity, should return true to stop further processing of this result
     * If the user has selected an widget, the result will be in the 'data' when this function is called.
     *
     * @param activity    The activity that received the result
     * @param requestCode The integer request code originally supplied to
     *                    startActivityForResult(), allowing you to identify who this
     *                    result came from.
     * @param resultCode  The integer result code returned by the child activity
     *                    through its setResult().
     * @param data        An Intent, which can return result data to the caller
     *                    (various data can be attached to Intent "extras").
     * @return if this result was processed here
     */
    public boolean onActivityResult(Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_PICK_APPWIDGET) {
                configureWidget(activity, data);
                return true;
            } else if (requestCode == REQUEST_CREATE_APPWIDGET) {
                createWidget(activity, data);
                return true;
            }
        } else if (resultCode == Activity.RESULT_CANCELED && data != null) {
            int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (appWidgetId != -1) {
                removeWidget(appWidgetId);
                return true;
            }
        }
        return false;
    }

    public int widgetCount() {
        return mWidgets.size();
    }

    /**
     * A popup with all active widgets to choose one
     *
     * @return the menu
     */
    public ListPopup getWidgetListPopup(@StringRes int title) {
        Context ctx = mLayout.getContext();
        LinearAdapter adapter = new LinearAdapterPlus();

        adapter.add(new LinearAdapter.ItemTitle(ctx, title));

        for (WidgetRecord rec : mWidgets.values()) {
            adapter.add(WidgetPopupItem.create(ctx, mAppWidgetManager, rec.appWidgetId));
        }

        return ListPopup.create(ctx, adapter);
    }

    /**
     * Popup with options for all widgets
     *
     * @param activity used to start the widget select popup
     * @return the popup menu
     */
    public ListPopup getConfigPopup(Activity activity) {
        LinearAdapter adapter = new LinearAdapter();

        adapter.add(new LinearAdapter.Item(activity, R.string.menu_widget_add));
        if (widgetCount() > 0) {
            adapter.add(new LinearAdapter.Item(activity, R.string.menu_widget_configure));
            adapter.add(new LinearAdapter.Item(activity, R.string.menu_widget_remove));
        }

        ListPopup menu = ListPopup.create(activity, adapter);
        menu.setOnItemClickListener((a, v, pos) -> {
            LinearAdapter.MenuItem item = ((LinearAdapter) a).getItem(pos);
            @StringRes int stringId = 0;
            if (item instanceof LinearAdapter.Item) {
                stringId = ((LinearAdapter.Item) a.getItem(pos)).stringId;
            }
            switch (stringId) {
                case R.string.menu_widget_add:
                    TBApplication.widgetManager(activity).selectWidget(activity);
                    break;
                case R.string.menu_widget_configure: {
                    ListPopup configWidgetPopup = TBApplication.widgetManager(activity).getWidgetListPopup(R.string.menu_widget_configure);
                    configWidgetPopup.setOnItemClickListener((a1, v1, pos1) -> {
                        Object item1 = a1.getItem(pos1);
                        if (item1 instanceof WidgetPopupItem) {
                            AppWidgetHostView widgetView = mLayout.getWidget(((WidgetPopupItem) item1).appWidgetId);
                            if (widgetView == null)
                                return;
                            ListPopup popup = getConfigPopup((WidgetView) widgetView);
                            TBApplication.behaviour(mLayout.getContext()).registerPopup(popup);
                            popup.show(widgetView, 0.f);
                        }
                    });

                    TBApplication.behaviour(activity).registerPopup(configWidgetPopup);
                    configWidgetPopup.showCenter(activity.getWindow().getDecorView());
                    break;
                }
                case R.string.menu_widget_remove: {
                    ListPopup removeWidgetPopup = TBApplication.widgetManager(activity).getWidgetListPopup(R.string.menu_widget_remove);
                    removeWidgetPopup.setOnItemClickListener((a1, v1, pos1) -> {
                        Object item1 = a1.getItem(pos1);
                        if (item1 instanceof WidgetPopupItem) {
                            removeWidget(((WidgetPopupItem) item1).appWidgetId);
                        }
                    });

                    TBApplication.behaviour(activity).registerPopup(removeWidgetPopup);
                    removeWidgetPopup.showCenter(activity.getWindow().getDecorView());
                    break;
                }
            }
        });
        return menu;
    }

    /**
     * Popup with options for the widget in the view
     *
     * @param view of the widget
     * @return the popup menu
     */
    protected ListPopup getConfigPopup(WidgetView view) {
        final int appWidgetId = view.getAppWidgetId();
        Context ctx = view.getContext();
        LinearAdapter adapter = new LinearAdapter();

        WidgetRecord widget = mWidgets.get(appWidgetId);
        if (widget != null) {
            final WidgetLayout.Handle handleType = mLayout.getHandleType(view);
            if (handleType.isMove()) {
                adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_move_switch, WidgetOptionItem.Action.MOVE_SWITCH));
                adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_move_exit, WidgetOptionItem.Action.RESET));
            } else {
                adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_move, WidgetOptionItem.Action.MOVE));
            }

            if (handleType.isResize()) {
                adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_resize_switch, WidgetOptionItem.Action.RESIZE_SWITCH));
                adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_resize_exit, WidgetOptionItem.Action.RESET));
            } else {
                adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_resize, WidgetOptionItem.Action.RESIZE));
            }

            adapter.add(new WidgetOptionItem(ctx, R.string.cfg_widget_remove, WidgetOptionItem.Action.REMOVE));

            if (BuildConfig.DEBUG) {
                adapter.add(new LinearAdapter.ItemTitle("Debug info"));
                adapter.add(new LinearAdapter.ItemString("ID: " + widget.appWidgetId));
                adapter.add(new LinearAdapter.ItemString("Name: " + getWidgetName(ctx, view.getAppWidgetInfo())));
                adapter.add(new LinearAdapter.ItemString("Width: " + widget.width));
                adapter.add(new LinearAdapter.ItemString("Height: " + widget.height));
            }
        } else {
            adapter.add(new LinearAdapter.ItemString("ERROR: Not found"));
        }

        ListPopup menu = ListPopup.create(ctx, adapter);

        menu.setOnItemClickListener((a, v, pos) -> {
            Object item = a.getItem(pos);
            if (item instanceof WidgetOptionItem) {
                switch (((WidgetOptionItem) item).mAction) {
                    case MOVE:
                        view.setOnClickListener(v1 -> {
                            view.setOnClickListener(null);
                            view.setOnDoubleClickListener(null);
                            mLayout.disableHandle(view);
                        });
                        view.setOnDoubleClickListener(v1 -> {
                            if (mLayout.getHandleType(view) == WidgetLayout.Handle.MOVE_FREE) {
                                mLastMoveType = WidgetLayout.Handle.MOVE_AXIAL;
                            } else {
                                mLastMoveType = WidgetLayout.Handle.MOVE_FREE;
                            }
                            mLayout.enableHandle(view, mLastMoveType);
                        });
                        mLayout.enableHandle(view, mLastMoveType);
                        break;
                    case MOVE_SWITCH:
                        if (mLayout.getHandleType(view) == WidgetLayout.Handle.MOVE_FREE) {
                            mLastMoveType = WidgetLayout.Handle.MOVE_AXIAL;
                        } else {
                            mLastMoveType = WidgetLayout.Handle.MOVE_FREE;
                        }
                        mLayout.enableHandle(view, mLastMoveType);
                        break;
                    case RESIZE:
                        view.setOnClickListener(v1 -> {
                            view.setOnClickListener(null);
                            view.setOnDoubleClickListener(null);
                            mLayout.disableHandle(view);
                        });
                        view.setOnDoubleClickListener(v1 -> {
                            if (mLayout.getHandleType(view) == WidgetLayout.Handle.RESIZE_DIAGONAL) {
                                mLastResizeType = WidgetLayout.Handle.RESIZE_AXIAL;
                            } else {
                                mLastResizeType = WidgetLayout.Handle.RESIZE_DIAGONAL;
                            }
                            mLayout.enableHandle(view, mLastResizeType);
                        });
                        mLayout.enableHandle(view, mLastResizeType);
                        break;
                    case RESIZE_SWITCH:
                        if (mLayout.getHandleType(view) == WidgetLayout.Handle.RESIZE_DIAGONAL) {
                            mLastResizeType = WidgetLayout.Handle.RESIZE_AXIAL;
                        } else {
                            mLastResizeType = WidgetLayout.Handle.RESIZE_DIAGONAL;
                        }
                        mLayout.enableHandle(view, mLastResizeType);
                        break;
                    case RESET:
                        view.setOnClickListener(null);
                        view.setOnDoubleClickListener(null);
                        mLayout.disableHandle(view);
                        break;
                    case REMOVE:
                        removeWidget(view);
                        break;
                }
            }
        });
        return menu;
    }

    /**
     * Scroll to page, just like the wallpaper
     *
     * @param scrollX horizontal scroll position 0.f .. 1.f
     * @param scrollY vertical scroll position 0.f .. 1.f
     */
    public void scroll(float scrollX, float scrollY) {
        if (mLayout == null)
            return;

        final int pageCountX = mLayout.getHorizontalPageCount();
        final float pageX = pageCountX * scrollX;

        final int pageCountY = mLayout.getVerticalPageCount();
        final float pageY = pageCountY * scrollY;

        mLayout.scrollToPage(pageX, pageY);
    }

    @NonNull
    public static String getWidgetName(Context ctx, AppWidgetProviderInfo info) {
        String name = null;
        if (info != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                name = info.loadLabel(ctx.getPackageManager());
            } else {
                name = info.label;
            }
        }
        return name == null ? "[null]" : name;
    }

    @NonNull
    public static Drawable getWidgetPreview(Context ctx, @Nullable AppWidgetProviderInfo info) {
        Drawable icon = null;
        if (info != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                icon = info.loadPreviewImage(ctx, 0);
            } else {
                icon = ctx.getPackageManager().getDrawable(info.provider.getPackageName(), info.previewImage, null);
            }
            if (icon == null) {
                try {
                    icon = ctx.getPackageManager().getApplicationLogo(info.provider.getPackageName());
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        if (icon == null) {
            if (info == null) {
                icon = ctx.getResources().getDrawable(R.drawable.ic_android);
            } else {
                try {
                    icon = ctx.getPackageManager().getApplicationIcon(info.provider.getPackageName());
                } catch (PackageManager.NameNotFoundException ignored) {
                    icon = ctx.getResources().getDrawable(R.drawable.ic_android);
                }
            }
        }
        return icon;
    }

    static class WidgetOptionItem extends LinearAdapter.Item {
        enum Action {
            MOVE,
            MOVE_SWITCH,
            RESIZE,
            RESIZE_SWITCH,
            RESET,
            REMOVE,
        }

        final Action mAction;

        public WidgetOptionItem(Context ctx, @StringRes int stringId, Action action) {
            super(ctx, stringId);
            mAction = action;
        }
    }

    static class WidgetPopupItem extends LinearAdapterPlus.ItemStringIcon {
        int appWidgetId;

        @NonNull
        static WidgetPopupItem create(Context ctx, AppWidgetManager appWidgetManager, int appWidgetId) {
            AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(appWidgetId);
            String name = getWidgetName(ctx, info);
            Drawable icon = getWidgetPreview(ctx, info);
            return new WidgetPopupItem(name, appWidgetId, icon);
        }

        private WidgetPopupItem(@NonNull String string, int appWidgetId, @NonNull Drawable icon) {
            super(string, icon);
            this.appWidgetId = appWidgetId;
        }

        @Override
        public int getLayoutResource() {
            return R.layout.popup_list_item_icon;
        }
    }

    static class WidgetHost extends AppWidgetHost {
        public WidgetHost(Context context) {
            super(context, APPWIDGET_HOST_ID);
        }

        @Override
        protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
            return new WidgetView(context);
        }
    }

}
