package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentFloatingButton;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Melgram: "scroll to top" button in the chat list. Appears above the floating
 * buttons once the list is scrolled a few chats down, like the "go down" button
 * in a chat; a tap jumps back to the newest chats.
 */
public final class MelgramScrollTop {

    private static final int SHOW_AFTER_POSITION = 4;

    private static final class State {
        FragmentFloatingButton button;
        boolean allowed = true;
        boolean scrolledDown;
    }

    private static final Map<DialogsActivity, State> states = new WeakHashMap<>();

    private MelgramScrollTop() {
    }

    private static State state(DialogsActivity fragment) {
        State s = states.get(fragment);
        if (s == null) {
            s = new State();
            states.put(fragment, s);
        }
        return s;
    }

    /** Called right after Telegram adds its own floating buttons. */
    public static void create(Context context, Theme.ResourcesProvider resourcesProvider, FrameLayout parent, DialogsActivity fragment) {
        try {
            if (!MelgramConfig.scrollTopButton()) {
                return;
            }
            FragmentFloatingButton button = new FragmentFloatingButton(context, resourcesProvider, true);
            button.setImageResource(R.drawable.msg_go_up);
            button.setContentDescription("Наверх");
            button.setButtonVisible(false, false);
            button.setOnClickListener(v -> {
                try {
                    fragment.scrollToTop(true, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
            parent.addView(button, FragmentFloatingButton.createSubButtonLayoutParams());
            State s = state(fragment);
            s.button = button;
            s.scrolledDown = false;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Called for every chat list page. */
    public static void watch(DialogsActivity fragment, RecyclerView listView) {
        if (listView == null) {
            return;
        }
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                try {
                    State s = states.get(fragment);
                    if (s == null || s.button == null) {
                        return;
                    }
                    RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                    if (!(lm instanceof LinearLayoutManager)) {
                        return;
                    }
                    int first = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
                    boolean down = first >= SHOW_AFTER_POSITION;
                    if (down != s.scrolledDown) {
                        s.scrolledDown = down;
                        apply(s, true);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    /** Mirrors Telegram's own rule for showing floating buttons (search, folders, selection...). */
    public static void setAllowed(DialogsActivity fragment, boolean allowed) {
        State s = states.get(fragment);
        if (s == null || s.button == null || s.allowed == allowed) {
            return;
        }
        s.allowed = allowed;
        apply(s, true);
    }

    /** Keeps the button above the story camera button, which sits above the "write" button. */
    public static void updateOffset(DialogsActivity fragment, float baseTranslationY, FragmentFloatingButton storiesButton) {
        State s = states.get(fragment);
        if (s == null || s.button == null) {
            return;
        }
        float y = baseTranslationY - AndroidUtilities.dp(52);
        if (storiesButton != null && storiesButton.getVisibility() == View.VISIBLE && storiesButton.getButtonVisible()) {
            y -= AndroidUtilities.dp(52);
        }
        s.button.setTranslationY(y);
    }

    public static void updateColors(DialogsActivity fragment) {
        State s = states.get(fragment);
        if (s != null && s.button != null) {
            s.button.updateColors();
        }
    }

    private static void apply(State s, boolean animated) {
        if (s.button != null) {
            s.button.setButtonVisible(s.allowed && s.scrolledDown, animated);
        }
    }
}
