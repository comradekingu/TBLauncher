package rocks.tbog.tblauncher;

public class LauncherState {
    enum AnimatedVisibility {
        HIDDEN,
        ANIM_TO_HIDDEN,
        ANIM_TO_VISIBLE,
        VISIBLE,
    }

    private AnimatedVisibility quickList = AnimatedVisibility.HIDDEN;
    private AnimatedVisibility searchBar = AnimatedVisibility.HIDDEN;
    private AnimatedVisibility resultList = AnimatedVisibility.HIDDEN;
    private AnimatedVisibility notificationBar = AnimatedVisibility.HIDDEN;
    private AnimatedVisibility widgets = AnimatedVisibility.HIDDEN;
    private AnimatedVisibility keyboard = AnimatedVisibility.HIDDEN;

    private static boolean isVisible(AnimatedVisibility state) {
        return state == AnimatedVisibility.ANIM_TO_VISIBLE ||
                state == AnimatedVisibility.VISIBLE;
    }

    public boolean isQuickListVisible() {
        return isVisible(quickList);
    }

    public boolean isSearchBarVisible() {
        return isVisible(searchBar);
    }

    public boolean isResultListVisible() {
        return isVisible(resultList);
    }

    public boolean isNotificationBarVisible() {
        return isVisible(notificationBar);
    }

    public boolean isWidgetVisible() {
        return isVisible(widgets);
    }

    public boolean isKeyboardVisible() {
        return isVisible(keyboard);
    }

    public void setNotificationBar(AnimatedVisibility state) {
        notificationBar = state;
    }

    public void setSearchBar(AnimatedVisibility state) {
        searchBar = state;
    }

    public void setResultList(AnimatedVisibility state) {
        resultList = state;
    }

    public void setQuickList(AnimatedVisibility state) {
        quickList = state;
    }

    public void setWidgets(AnimatedVisibility state) {
        widgets = state;
    }

    public void setKeyboard(AnimatedVisibility state) {
        keyboard = state;
    }
}
