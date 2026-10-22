package org.telegram.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MelgramIconTools;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AppIconsSelectorCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Melgram: built-in launcher icons plus any picture from the gallery as a home-screen icon. */
public class MelgramIconFragment extends UniversalFragment {

    private static final int REQ_PICK = 4101;
    private static final int ID_PICK = 1;
    private static final int ID_PIN = 2;
    private static final int ID_RESET = 3;

    private Bitmap source;
    private Bitmap rendered;
    private int scale;
    private boolean busy;

    private AppIconsSelectorCell selector;
    private FrameLayout previewContainer;
    private ImageView preview;

    @Override
    public View createView(Context context) {
        source = MelgramIconTools.loadSource(context);
        scale = MelgramIconTools.getScale(context);

        selector = new AppIconsSelectorCell(context, this, currentAccount);

        previewContainer = new FrameLayout(context);
        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewContainer.addView(preview, LayoutHelper.createFrame(132, 132, Gravity.CENTER));
        render();

        return super.createView(context);
    }

    @Override
    protected CharSequence getTitle() {
        return "Иконка приложения";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Значок приложения"));
        items.add(UItem.asCustom(selector));
        items.add(UItem.asShadow("Меняет настоящий значок Melgram — на рабочем столе и в меню приложений. Значок обновится через пару секунд."));

        items.add(UItem.asHeader("Своя картинка из галереи"));
        items.add(UItem.asCustom(previewContainer, 160));
        items.add(UItem.asIntSlideView(1, MelgramIconTools.SCALE_MIN, scale, 100,
                value -> value + "%",
                value -> {
                    scale = value;
                    Context context = getContext();
                    if (context != null) MelgramIconTools.setScale(context, value);
                    render();
                }));
        items.add(UItem.asButton(ID_PICK, R.drawable.msg_gallery, "Выбрать из галереи"));
        items.add(UItem.asButton(ID_PIN, R.drawable.msg_add, "Поставить на рабочий стол").accent());
        items.add(UItem.asButton(ID_RESET, R.drawable.msg_reset, "Вернуть стандартную картинку"));
        items.add(UItem.asShadow("Melgram сам обрежет картинку под формат иконки, ползунок меняет её размер. " +
                "Android не разрешает ставить на значок приложения произвольную картинку, поэтому она появится " +
                "на рабочем столе ярлыком Melgram. Старый значок с рабочего стола можно убрать — в меню приложений он останется."));
    }

    private void render() {
        if (source == null || preview == null) return;
        rendered = MelgramIconTools.render(source, scale);
        preview.setImageBitmap(MelgramIconTools.circlePreview(rendered));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        Activity activity = getParentActivity();
        if (activity == null || busy) return;
        if (item.id == ID_PICK) {
            pick(activity);
        } else if (item.id == ID_PIN) {
            if (rendered == null) return;
            try {
                int result = MelgramIconTools.pin(activity, rendered);
                if (result == MelgramIconTools.PIN_UPDATED) {
                    bulletin(R.raw.done, "Иконка на рабочем столе обновлена");
                } else if (result == MelgramIconTools.PIN_UNSUPPORTED) {
                    bulletin(R.raw.error, "Лаунчер не умеет добавлять ярлыки");
                }
            } catch (Throwable e) {
                FileLog.e(e);
                bulletin(R.raw.error, "Не получилось добавить ярлык");
            }
        } else if (item.id == ID_RESET) {
            MelgramIconTools.resetSource(activity);
            source = MelgramIconTools.loadSource(activity);
            scale = MelgramIconTools.getScale(activity);
            render();
            MelgramIconTools.updateIfPinned(activity, rendered);
            listView.adapter.update(true);
            bulletin(R.raw.done, "Вернул стандартную картинку");
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void pick(Activity activity) {
        Intent pick = new Intent(Intent.ACTION_PICK);
        pick.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        try {
            activity.startActivityForResult(pick, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Intent get = new Intent(Intent.ACTION_GET_CONTENT);
            get.setType("image/*");
            get.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                activity.startActivityForResult(Intent.createChooser(get, "Выбери картинку"), REQ_PICK);
            } catch (Throwable e2) {
                bulletin(R.raw.error, "Не нашёл приложение галереи");
            }
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Context context = getParentActivity();
        if (context == null) return;
        final Uri uri = data.getData();
        busy = true;
        new Thread(() -> {
            Bitmap picture = null;
            try {
                picture = MelgramIconTools.decodeSquare(context, uri);
                MelgramIconTools.saveSource(context, picture);
            } catch (Throwable e) {
                FileLog.e(e);
                picture = null;
            }
            final Bitmap result = picture;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (isFinishing()) return;
                if (result == null) {
                    bulletin(R.raw.error, "Не получилось открыть эту картинку");
                    return;
                }
                source = result;
                render();
                bulletin(R.raw.done, "Готово! Теперь нажми «Поставить на рабочий стол»");
            });
        }, "melgram-icon").start();
    }

    private void bulletin(int raw, String text) {
        BulletinFactory.of(this).createSimpleBulletin(raw, text).show();
    }
}
