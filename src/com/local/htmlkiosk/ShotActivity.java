package com.local.htmlkiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 截图标注页：整页缩放到屏幕内，手指画红笔标记，
 * 然后保存到相册或直接分享给 ChatGPT（图片附带在聊天输入框里）。
 */
public class ShotActivity extends Activity {

    private static final int WG = LinearLayout.LayoutParams.WRAP_CONTENT;
    private static final int MP = LinearLayout.LayoutParams.MATCH_PARENT;

    private DrawView draw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String path = getIntent() != null ? getIntent().getStringExtra("path") : null;
        Bitmap bmp = null;
        try {
            if (path != null) bmp = BitmapFactory.decodeFile(path);
        } catch (Throwable ignored) {
        }
        if (bmp == null) {
            toast("截图已失效，请重新截一次");
            finish();
            return;
        }

        draw = new DrawView(this, bmp);
        draw.setTextPointListener(new DrawView.TextPointListener() {
            @Override
            public void onTextPoint(float x, float y) {
                promptText(x, y);
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1B2430);
        root.addView(buildBar(), new LinearLayout.LayoutParams(MP, WG));
        root.addView(draw, new LinearLayout.LayoutParams(MP, 0, 1f));
        setContentView(root);
        goImmersive();

        pruneOldShots();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private void goImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ---------------------------------------------------------------- toolbar

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(10), dp(18), dp(10));
        bar.setBackgroundColor(0xF2FFFFFF);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text("涂鸦标注", 16, 0xFF0F2038, Typeface.BOLD);
        TextView t2 = text("单指涂鸦 · 双指缩放/上下滑 · 再发 ChatGPT", 11, 0xFF7B8CA3, Typeface.NORMAL);
        titles.addView(t1);
        titles.addView(t2);
        bar.addView(titles);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(btn("撤销", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (draw != null) draw.undo();
            }
        }));
        bar.addView(btn("清空", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (draw != null) draw.clear();
            }
        }));
        bar.addView(btn("文字", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (draw != null) {
                    draw.setTextMode(true);
                    toast("点一下要放文字的位置");
                }
            }
        }));
        bar.addView(btn("－", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (draw != null) draw.zoomBy(1f / 1.5f);
            }
        }));
        bar.addView(btn("＋", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (draw != null) draw.zoomBy(1.5f);
            }
        }));
        bar.addView(btn("保存到相册", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToGallery();
            }
        }));
        bar.addView(btn("发给 ChatGPT", 0xFFFFFFFF, 0xFF128076, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareToChatGpt();
            }
        }));
        bar.addView(btn("关闭", 0xFF284262, 0xFFF4F8FD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        return bar;
    }

    private TextView btn(String label, int fg, int bg, View.OnClickListener l) {
        TextView v = text(label, 13, fg, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(99));
        v.setBackground(d);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WG, WG);
        lp.leftMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    /** 点完位置后弹输入框，确认后把文字画到图上的那个点。 */
    private void promptText(final float x, final float y) {
        final EditText input = new EditText(this);
        input.setHint("输入要写在图上的文字（可换行）");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setGravity(Gravity.TOP | Gravity.START);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("文字说明")
                .setView(input)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String t = input.getText().toString().trim();
                        if (t.length() > 0) draw.addText(x, y, t);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        d.show();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    // ----------------------------------------------------------------- share

    private void shareToChatGpt() {
        if (draw == null) return;
        final Bitmap out = draw.render();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File f = new File(ShotProvider.dir(ShotActivity.this),
                        "share-" + System.currentTimeMillis() + ".jpg");
                boolean ok = false;
                try {
                    FileOutputStream os = new FileOutputStream(f);
                    ok = out.compress(Bitmap.CompressFormat.JPEG, 90, os);
                    os.close();
                } catch (Throwable t) {
                    ok = false;
                }
                out.recycle();
                final boolean done = ok;
                final File file = f;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!done) {
                            toast("生成图片失败");
                            return;
                        }
                        Uri uri = ShotProvider.uriFor(file.getName());
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("image/jpeg");
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_TEXT, "请帮我看看这张图。");
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent direct = new Intent(send);
                        direct.setPackage("com.openai.chatgpt");
                        try {
                            startActivity(direct);
                        } catch (Throwable t) {
                            try {
                                startActivity(Intent.createChooser(send, "发送给…"));
                            } catch (Throwable t2) {
                                toast("没有可用的分享应用");
                            }
                        }
                    }
                });
            }
        }, "shot-share").start();
    }

    private void saveToGallery() {
        if (draw == null) return;
        Bitmap out = draw.render();
        String name = "shot-" + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/课件截图");
                Uri u = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IllegalStateException("insert failed");
                OutputStream os = getContentResolver().openOutputStream(u);
                out.compress(Bitmap.CompressFormat.JPEG, 90, os);
                os.close();
                toast("已保存到相册 · Pictures/课件截图");
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "课件截图");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream os = new FileOutputStream(f);
                out.compress(Bitmap.CompressFormat.JPEG, 90, os);
                os.close();
                toast("已保存：" + f.getAbsolutePath());
            }
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
        } finally {
            out.recycle();
        }
    }

    /** 只保留最近 20 张临时图，避免缓存越积越多。 */
    private void pruneOldShots() {
        File[] fs = ShotProvider.dir(this).listFiles();
        if (fs == null || fs.length <= 20) return;
        Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = 20; i < fs.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
        }
    }

    // ----------------------------------------------------------------- utils

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String content, float spValue, int color, int style) {
        TextView v = new TextView(this);
        v.setText(content);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, spValue);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, style);
        v.setIncludeFontPadding(false);
        return v;
    }

    // -------------------------------------------------------------- draw view

    /** 整张图等比缩放到视图内，手指轨迹按图片原始坐标记录。 */
    private static class DrawView extends View {

        interface TextPointListener {
            void onTextPoint(float x, float y);
        }

        private static class TextItem {
            final float x, y;
            final String[] lines;

            TextItem(float x, float y, String text) {
                this.x = x;
                this.y = y;
                this.lines = text.split("\n", -1);
            }
        }

        private final Bitmap base;
        private final Paint ink = new Paint();
        private final Paint textPaint = new Paint();
        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix fit = new Matrix();
        private final Matrix inv = new Matrix();
        private final List<Object> items = new ArrayList<Object>();
        private final float[] pt = new float[2];
        private Path current;
        private boolean textMode;
        private TextPointListener textListener;
        private float downX, downY;
        private final float textSize;
        private final Paint barPaint = new Paint();
        private final RectF barRect = new RectF();

        // 缩放/平移：长图默认按宽度显示，双指缩放、拖动
        private float viewW, viewH;
        private float minScale = 1f, maxScale = 1f, defScale = 1f;
        private float scale = 1f;
        private float tx, ty;
        private boolean gesture;
        private float lastSpan, lastMidX, lastMidY;

        DrawView(Context c, Bitmap b) {
            super(c);
            base = b;
            ink.setColor(0xFFE53935);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setAntiAlias(true);
            ink.setStrokeWidth(Math.max(4f, b.getWidth() / 260f));

            textSize = Math.max(20f, b.getWidth() / 64f);
            textPaint.setColor(0xFFE53935);
            textPaint.setAntiAlias(true);
            textPaint.setTextSize(textSize);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);

            barPaint.setColor(0x99284262);
            barPaint.setAntiAlias(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            viewW = w;
            viewH = h;
            float fitW = w / (float) base.getWidth();
            float fitH = h / (float) base.getHeight();
            minScale = Math.min(fitW, fitH);
            // 长图：按宽度显示（可上下滑）；普通图：整张放进屏幕
            boolean tall = base.getHeight() * fitW > h + 0.5f;
            defScale = tall ? fitW : minScale;
            maxScale = Math.max(defScale * 5f, minScale * 5f);
            scale = defScale;
            tx = 0;
            ty = 0;
            clampTranslation();
            rebuild();
        }

        /** 双指缩放后保持画面中心，并限制不要拖出边界。 */
        private void clampTranslation() {
            float cw = base.getWidth() * scale;
            float ch = base.getHeight() * scale;
            if (cw <= viewW) tx = (viewW - cw) / 2f;
            else tx = Math.max(viewW - cw, Math.min(0f, tx));
            if (ch <= viewH) ty = (viewH - ch) / 2f;
            else ty = Math.max(viewH - ch, Math.min(0f, ty));
        }

        private void rebuild() {
            fit.setScale(scale, scale);
            fit.postTranslate(tx, ty);
            fit.invert(inv);
            invalidate();
        }

        void zoomBy(float f) {
            float ns = Math.max(minScale, Math.min(maxScale, scale * f));
            float real = ns / scale;
            float cx = viewW / 2f;
            float cy = viewH / 2f;
            tx = cx - real * (cx - tx);
            ty = cy - real * (cy - ty);
            scale = ns;
            clampTranslation();
            rebuild();
        }

        private float span(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0f;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return (float) Math.hypot(dx, dy);
        }

        private float mid(MotionEvent e, boolean xAxis) {
            if (e.getPointerCount() >= 2) {
                return (xAxis ? e.getX(0) + e.getX(1) : e.getY(0) + e.getY(1)) / 2f;
            }
            return xAxis ? e.getX() : e.getY();
        }

        private void paintItems(Canvas c) {
            for (Object o : items) {
                if (o instanceof Path) {
                    c.drawPath((Path) o, ink);
                } else {
                    drawTextItem(c, (TextItem) o);
                }
            }
        }

        private void drawTextItem(Canvas c, TextItem t) {
            float lh = textSize * 1.35f;
            float x = Math.max(0, Math.min(t.x, base.getWidth() - 1));
            float y = Math.max(0, Math.min(t.y, base.getHeight() - 1));
            float baseline = y + textSize;
            for (String line : t.lines) {
                c.drawText(line, x, baseline, textPaint);
                baseline += lh;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF1B2430);
            c.drawBitmap(base, fit, imagePaint);
            c.save();
            c.concat(fit);
            paintItems(c);
            if (current != null) c.drawPath(current, ink);
            c.restore();
            if (viewH > 0 && base.getHeight() * scale > viewH + 0.5f) {
                drawScrollBar(c);
            }
        }

        /** 右侧细滚动条，提示长图当前位置。 */
        private void drawScrollBar(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float pad = 10f * d;
            float trackTop = pad;
            float trackH = viewH - pad * 2f;
            if (trackH <= 0) return;
            float contentH = base.getHeight() * scale;
            float thumbH = Math.max(48f * d, trackH * viewH / contentH);
            float room = trackH - thumbH;
            float t = contentH > viewH ? (-ty) / (contentH - viewH) : 0f;
            t = Math.max(0f, Math.min(1f, t));
            float top = trackTop + room * t;
            float right = viewW - 6f * d;
            barRect.set(right - 6f * d, top, right, top + thumbH);
            c.drawRoundRect(barRect, 3f * d, 3f * d, barPaint);
        }

        void setTextMode(boolean on) {
            textMode = on;
        }

        void setTextPointListener(TextPointListener l) {
            textListener = l;
        }

        void addText(float x, float y, String text) {
            items.add(new TextItem(x, y, text));
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            int count = e.getPointerCount();

            // 双指：缩放 + 拖动查看长图；单指始终用于涂鸦
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                gesture = true;
                textMode = false;
                current = null;
                lastSpan = span(e);
                lastMidX = mid(e, true);
                lastMidY = mid(e, false);
                invalidate();
                return true;
            }
            if (gesture) {
                if (action == MotionEvent.ACTION_MOVE && count >= 2) {
                    float s = span(e);
                    float mx = mid(e, true);
                    float my = mid(e, false);
                    if (lastSpan > 10f && s > 10f) {
                        float ns = Math.max(minScale,
                                Math.min(maxScale, scale * (s / lastSpan)));
                        float f = ns / scale;
                        tx = mx - f * (lastMidX - tx);
                        ty = my - f * (lastMidY - ty);
                        scale = ns;
                        clampTranslation();
                        rebuild();
                    }
                    lastSpan = s;
                    lastMidX = mx;
                    lastMidY = my;
                    return true;
                }
                if (action == MotionEvent.ACTION_POINTER_UP
                        || action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    gesture = false;
                    return true;
                }
                return true;
            }

            pt[0] = e.getX();
            pt[1] = e.getY();
            inv.mapPoints(pt);
            boolean inside = pt[0] >= 0 && pt[1] >= 0
                    && pt[0] <= base.getWidth() && pt[1] <= base.getHeight();

            if (textMode) {
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = pt[0];
                    downY = pt[1];
                    return inside;
                }
                if (action == MotionEvent.ACTION_UP) {
                    textMode = false;
                    if (inside && textListener != null
                            && Math.hypot(pt[0] - downX, pt[1] - downY) < textSize) {
                        textListener.onTextPoint(pt[0], pt[1]);
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) textMode = false;
                return inside;
            }

            if (action == MotionEvent.ACTION_DOWN) {
                if (!inside) return false;
                current = new Path();
                current.moveTo(pt[0], pt[1]);
                invalidate();
                return true;
            }
            if (current == null) return false;
            if (action == MotionEvent.ACTION_MOVE) {
                current.lineTo(pt[0], pt[1]);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                items.add(current);
                current = null;
                invalidate();
                return true;
            }
            return false;
        }

        void undo() {
            if (!items.isEmpty()) items.remove(items.size() - 1);
            invalidate();
        }

        void clear() {
            items.clear();
            current = null;
            invalidate();
        }

        /** 按原图分辨率重绘（长边不超过 4096，方便 ChatGPT 上传）。 */
        Bitmap render() {
            int bw = base.getWidth();
            int bh = base.getHeight();
            float s = Math.min(1f, 4096f / Math.max(bw, bh));
            int ow = Math.max(1, Math.round(bw * s));
            int oh = Math.max(1, Math.round(bh * s));
            Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(base, null, new RectF(0, 0, ow, oh), imagePaint);
            c.scale(ow / (float) bw, oh / (float) bh);
            paintItems(c);
            return out;
        }
    }
}