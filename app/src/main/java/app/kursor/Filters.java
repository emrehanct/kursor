package app.kursor;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reklam süzgeci listeleri: yükleme ve sessiz otomatik güncelleme.
 * Ad filter lists: loading and silent automatic updates.
 *
 * - İlk kurulumda uygulamaya gömülü listeler kullanılır (CI derlerken en güncelini koyar).
 *   On first install the lists bundled in the app are used (CI bundles the latest at build time).
 * - Uygulama açıkken ve kapalıyken (JobScheduler) düzenli aralıklarla "değiştiyse indir" denetimi yapılır;
 *   yeni liste yayınlandıysa indirilir, arka planda derlenir ve fark ettirmeden devreye girer.
 *   While the app is open and in the background (JobScheduler), a conditional "download if changed"
 *   check runs periodically; a newly published list is downloaded, built in the background and
 *   swapped in silently.
 * - Sunucuya yalnızca herkese açık liste dosyaları istenir; kullanıcı verisi gönderilmez.
 *   Only the public list files are requested; no user data is sent.
 */
public final class Filters {

    /** {kimlik, adres} — AdGuard filtreleri (GPL-3.0), uBlock uyumlu sürümleri / AdGuard filters (GPL-3.0), uBlock-compatible builds */
    static final String[][] LISTS = {
            {"2", "https://filters.adtidy.org/extension/ublock/filters/2.txt"},    // AdGuard Base + EasyList
            {"11", "https://filters.adtidy.org/extension/ublock/filters/11.txt"},  // AdGuard Mobile Ads
            {"13", "https://filters.adtidy.org/extension/ublock/filters/13.txt"},  // AdGuard Turkish
    };

    private static final long HOUR = 3600_000L;
    private static final long INTERVAL = 12 * HOUR;          // ücretsiz ağda / on unmetered networks
    private static final long INTERVAL_METERED = 24 * HOUR;  // mobil veride / on mobile data
    private static final int JOB_ID = 4711;

    private static volatile AdBlocker engine = AdBlocker.empty();
    private static volatile boolean loaded = false;
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Filters() { }

    public static AdBlocker engine() { return engine; }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("kursor_filters", Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "filters");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Uygulama açılışında: listeleri yükle, gerekirse güncelle, arka plan işini kur. */
    public static void start(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        exec.execute(new Runnable() {
            @Override public void run() {
                if (!loaded) rebuild(app);
                checkNow(app, false);
            }
        });
        schedule(app);
    }

    /** Uygulama açıkken düzenli çağrılır; zamanı geldiyse sessizce güncelle. */
    public static void tick(Context ctx) {
        final Context app = ctx.getApplicationContext();
        exec.execute(new Runnable() { @Override public void run() { checkNow(app, false); } });
    }

    /** Listeleri diskten (yoksa uygulama içinden) okuyup motoru yeniden kurar. */
    private static void rebuild(Context app) {
        List<Reader> readers = new ArrayList<>();
        try {
            for (String[] l : LISTS) {
                File f = new File(dir(app), l[0] + ".txt");
                InputStream in = null;
                if (f.length() > 0) in = new FileInputStream(f);
                else {
                    try { in = app.getAssets().open("adblock/" + l[0] + ".txt"); }
                    catch (Exception ignored) { }
                }
                if (in != null) readers.add(new InputStreamReader(in, "UTF-8"));
            }
            AdBlocker next = AdBlocker.build(readers);
            engine = next;
            loaded = true;
        } catch (Throwable t) {
            loaded = true;   // bozuk liste: eski motor kalır / broken list: keep the old engine
        } finally {
            for (Reader r : readers) try { r.close(); } catch (Exception ignored) { }
        }
    }

    private static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    /** Zamanı geldiyse her listeyi "değiştiyse indir" ile denetler. İş parçacığında çalışır. */
    static boolean checkNow(Context app, boolean force) {
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        long last = p.getLong("lastCheck", 0);
        long every = metered(app) ? INTERVAL_METERED : INTERVAL;
        if (!force && now - last < every && now >= last) return false;
        boolean changed = false, allOk = true;
        for (String[] l : LISTS) {
            try {
                if (download(app, l[0], l[1])) changed = true;
            } catch (Exception e) {
                allOk = false;
            }
        }
        // Başarısız denemede bir saat sonra yeniden dene / on failure retry in about an hour
        p.edit().putLong("lastCheck", allOk ? now : now - every + HOUR).apply();
        if (changed) {
            p.edit().putLong("lastUpdate", now).apply();
            if (loaded) rebuild(app);   // uygulama açıksa hemen devreye al / apply now if the app is running
        }
        return changed;
    }

    /** Liste yeniyse indirir (ETag / Last-Modified). Yeni sürüm indirildiyse true. */
    private static boolean download(Context app, String id, String url) throws Exception {
        SharedPreferences p = prefs(app);
        File target = new File(dir(app), id + ".txt");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Kursor");
        if (target.length() > 0) {
            String etag = p.getString("etag_" + id, null), mod = p.getString("mod_" + id, null);
            if (etag != null) c.setRequestProperty("If-None-Match", etag);
            if (mod != null) c.setRequestProperty("If-Modified-Since", mod);
        }
        try {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return false;
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code);
            File tmp = new File(dir(app), id + ".tmp");
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[1 << 15];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
            if (!looksLikeList(tmp)) { tmp.delete(); throw new Exception("not a filter list"); }
            if (!tmp.renameTo(target)) { tmp.delete(); throw new Exception("rename failed"); }
            p.edit().putString("etag_" + id, c.getHeaderField("ETag"))
                    .putString("mod_" + id, c.getHeaderField("Last-Modified")).apply();
            return true;
        } finally {
            c.disconnect();
        }
    }

    /** Yarım ya da hatalı indirmeleri ayıklar / rejects truncated or wrong downloads */
    private static boolean looksLikeList(File f) {
        if (f.length() < 2000) return false;
        try {
            InputStream in = new FileInputStream(f);
            byte[] head = new byte[512];
            int n = in.read(head);
            in.close();
            String s = new String(head, 0, Math.max(0, n), "UTF-8");
            return s.contains("! Title") || s.startsWith("[Adblock");
        } catch (Exception e) {
            return false;
        }
    }

    /** Uygulama kapalıyken de denetim için periyodik iş / periodic job for checks while the app is closed */
    private static void schedule(Context app) {
        try {
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(app, Job.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(INTERVAL)
                    .build());
        } catch (Throwable ignored) { }
    }

    /** Arka plan işi / background job */
    public static class Job extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            final Context app = getApplicationContext();
            exec.execute(new Runnable() {
                @Override public void run() {
                    try { checkNow(app, false); } catch (Throwable ignored) { }
                    jobFinished(params, false);
                }
            });
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) { return true; }
    }
}
