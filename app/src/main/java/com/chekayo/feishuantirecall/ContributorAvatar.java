package com.chekayo.feishuantirecall;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads small contributor avatars on demand; initials remain visible offline. */
final class ContributorAvatar {
    private static final ExecutorService LOADERS = Executors.newFixedThreadPool(2);
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<String, Bitmap> CACHE = new ConcurrentHashMap<String, Bitmap>();
    private static final Object PENDING_LOCK = new Object();
    private static final Map<String, List<WeakReference<ImageView>>> PENDING =
            new HashMap<String, List<WeakReference<ImageView>>>();

    static FrameLayout create(final Context context, final String username, String initial, int sizeDp) {
        final FrameLayout frame = new FrameLayout(context);
        int size = Ui.dp(context, sizeDp);
        frame.setLayoutParams(new android.view.ViewGroup.LayoutParams(size, size));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.isNight(context) ? 0xFF34445D : 0xFFDCEBFA);
        frame.setBackground(circle);
        frame.setClipToOutline(true);

        TextView fallback = new TextView(context);
        fallback.setText(initial);
        fallback.setTextSize(17);
        fallback.setTypeface(null, android.graphics.Typeface.BOLD);
        fallback.setTextColor(Ui.ACCENT);
        fallback.setGravity(android.view.Gravity.CENTER);
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

        final ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        Bitmap cached = CACHE.get(username);
        if (cached != null) image.setImageBitmap(cached);
        else load(username, image);
        return frame;
    }

    private static void load(final String username, final ImageView target) {
        boolean shouldStart = false;
        synchronized (PENDING_LOCK) {
            Bitmap cached = CACHE.get(username);
            if (cached != null) {
                target.setImageBitmap(cached);
                return;
            }
            List<WeakReference<ImageView>> waiters = PENDING.get(username);
            if (waiters == null) {
                waiters = new ArrayList<WeakReference<ImageView>>();
                PENDING.put(username, waiters);
                shouldStart = true;
            }
            waiters.add(new WeakReference<ImageView>(target));
        }
        if (!shouldStart) return;
        LOADERS.execute(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                final List<WeakReference<ImageView>> waiters;
                try {
                    bitmap = download(username);
                } finally {
                    synchronized (PENDING_LOCK) {
                        if (bitmap != null) CACHE.put(username, bitmap);
                        waiters = PENDING.remove(username);
                    }
                }
                if (bitmap == null || waiters == null || waiters.isEmpty()) return;
                final Bitmap result = bitmap;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        for (int i = 0; i < waiters.size(); i++) {
                            ImageView iv = waiters.get(i).get();
                            if (iv != null && iv.isAttachedToWindow()) {
                                iv.setImageBitmap(result);
                            }
                        }
                    }
                });
            }
        });
    }

    private static Bitmap download(String username) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            URL url = new URL("https://github.com/" + username + ".png?size=192");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "FeishuKit-About");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0, read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 512 * 1024) return null;
                output.write(buffer, 0, read);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size(), options);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private ContributorAvatar() {}
}
