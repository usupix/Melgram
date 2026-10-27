package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.utils.CopyUtilities;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.QuoteSpan;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Melgram: formatted paste from the keyboard's clipboard.
 *
 * Telegram puts formatting (bold, links, premium emoji…) into the clipboard as
 * HTML, but only restores it when you paste from the long-press menu. Keyboard
 * clipboard panels (Gboard, Samsung…) insert plain text. Melgram remembers the
 * HTML of everything copied in the app, and when exactly that text is typed in
 * by the keyboard, inserts the formatted version instead.
 */
public final class MelgramClipboard {

    private static final int MAX = 30;
    private static final String PREFS = "melgram_clipboard";
    private static final LinkedHashMap<String, String> history = new LinkedHashMap<>(16, 0.75f, true);
    private static boolean loaded;

    private MelgramClipboard() {
    }

    private static boolean enabled() {
        return MelgramConfig.get(MelgramConfig.RICH_PASTE);
    }

    private static boolean hasFormatting(String html) {
        if (html == null) return false;
        String h = html.toLowerCase();
        return h.contains("<b>") || h.contains("<i>") || h.contains("<u>") || h.contains("<s>") || h.contains("<a ")
                || h.contains("<animated-emoji") || h.contains("<code>") || h.contains("<pre") || h.contains("<spoiler>")
                || h.contains("<blockquote");
    }

    /** Called by AndroidUtilities whenever Telegram copies text with HTML. */
    public static void remember(CharSequence plain, String html) {
        try {
            if (plain == null || plain.length() == 0 || !hasFormatting(html) || !enabled()) {
                return;
            }
            synchronized (history) {
                load();
                history.remove(plain.toString());
                history.put(plain.toString(), html);
                while (history.size() > MAX) {
                    Iterator<String> it = history.keySet().iterator();
                    it.next();
                    it.remove();
                }
                save();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String htmlFor(CharSequence text) {
        if (text == null || text.length() == 0) return null;
        synchronized (history) {
            load();
            return history.get(text.toString());
        }
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Context c = ApplicationLoader.applicationContext;
            if (c == null) return;
            String json = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null);
            if (json == null) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                history.put(o.getString("p"), o.getString("h"));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void save() {
        try {
            Context c = ApplicationLoader.applicationContext;
            if (c == null) return;
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : history.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("p", e.getKey());
                o.put("h", e.getValue());
                arr.put(o);
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void clear() {
        synchronized (history) {
            history.clear();
            loaded = true;
            Context c = ApplicationLoader.applicationContext;
            if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    /** Wraps the input connection of Telegram's text fields. */
    public static InputConnection wrap(EditText editText, InputConnection ic) {
        if (ic == null) return null;
        return new InputConnectionWrapper(ic, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                try {
                    if (enabled()) {
                        String html = htmlFor(text);
                        if (html != null) {
                            finishComposingText();
                            if (insertHtml(editText, html)) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                return super.commitText(text, newCursorPosition);
            }
        };
    }

    /** Same steps as Telegram's own "Paste" with HTML (EditTextCaption.onTextContextMenuItem). */
    private static boolean insertHtml(EditText editText, String html) {
        CharSequence parsed = CopyUtilities.fromHTML(html);
        if (parsed == null) return false;
        SpannableStringBuilder pasted = new SpannableStringBuilder(parsed);
        Emoji.replaceEmoji(pasted, editText.getPaint().getFontMetricsInt(), false, null);
        AnimatedEmojiSpan[] spans = pasted.getSpans(0, pasted.length(), AnimatedEmojiSpan.class);
        if (spans != null) {
            for (AnimatedEmojiSpan span : spans) {
                span.applyFontMetrics(editText.getPaint().getFontMetricsInt(), AnimatedEmojiDrawable.getCacheTypeForEnterView());
            }
        }
        Editable text = editText.getText();
        int start = Math.max(0, Math.min(editText.getSelectionStart(), editText.getSelectionEnd()));
        int end = Math.min(text.length(), Math.max(editText.getSelectionStart(), editText.getSelectionEnd()));
        QuoteSpan.QuoteStyleSpan[] quotesInSelection = text.getSpans(start, end, QuoteSpan.QuoteStyleSpan.class);
        if (quotesInSelection != null && quotesInSelection.length > 0) {
            QuoteSpan.QuoteStyleSpan[] quotesToDelete = pasted.getSpans(0, pasted.length(), QuoteSpan.QuoteStyleSpan.class);
            for (QuoteSpan.QuoteStyleSpan q : quotesToDelete) {
                pasted.removeSpan(q);
                pasted.removeSpan(q.span);
            }
        } else {
            QuoteSpan.normalizeQuotes(pasted);
        }
        text.replace(start, end, pasted);
        int pos = Math.min(text.length(), start + pasted.length());
        editText.setSelection(pos, pos);
        return true;
    }
}
