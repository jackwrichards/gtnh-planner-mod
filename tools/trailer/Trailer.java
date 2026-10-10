import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

/**
 * Cuts frames from the dev recorder ({@code call 'record?start=name&fps=60&width=1920&threads=10&cursor=0'}, which
 * also writes frames.csv: each frame's time, the tour's pointer and step) into a graded, camera-moved edit, piped to
 * ffmpeg with its music. A shot file says it all (teaser.shot has every line there is):
 *
 * <pre>
 * java tools/trailer/Trailer.java tools/trailer/teaser.shot              # the video
 * java tools/trailer/Trailer.java tools/trailer/teaser.shot --stills     # only the stills the shot lists
 * java tools/trailer/Trailer.java tools/trailer/teaser.shot --every 0.5  # a still every half second, to review
 * </pre>
 *
 * Each shot takes a span of a recording onto a span of the edit (straight, or through remap points for speed ramps)
 * and looks at the game's frame as a plane in space: Dutch roll, a slight lean, zoom, a touch of barrel, the tour's
 * pointer followed. Then depth of field, bloom, grade, vignette, grain and a title over all of it.
 */
public class Trailer {

    // region The edit

    static int W = 1920, H = 1080;
    static double FPS = 60, DURATION = 5, BPM = 120;
    static File out, stillDir;
    static final Map<String, Source> sources = new HashMap<>();
    static final List<Shot> shots = new ArrayList<>();
    static final Map<String, List<double[]>> globalKeys = new HashMap<>();
    static final Map<String, String> globalSet = new HashMap<>();
    static final List<Double> stills = new ArrayList<>();
    /** Words over the picture: when, and the text (between asterisks, the brand's cyan). */
    record Caption(double t0, double t1, String text, boolean badge, boolean top) {}

    static final List<Caption> captions = new ArrayList<>();
    static String title = "";
    static Source defaultSource;

    static final class Shot {

        double t0, t1;
        Source src;
        final List<String[]> pendingRemap = new ArrayList<>();
        final List<double[]> remap = new ArrayList<>();
        final Map<String, List<double[]>> keys = new HashMap<>();
        final Map<String, String> set = new HashMap<>();
        String name = "";
        // Per output frame of the shot: the smoothed pointer and the least zoom that hides the frame's edges.
        double[] pointer, zoomFloor;
    }

    static double beat() {
        return 60 / BPM;
    }

    /** A time in the edit: seconds, or beats ("8b"). */
    static double editTime(final String s) {
        return s.endsWith("b") ? Double.parseDouble(s.substring(0, s.length() - 1)) * beat() : Double.parseDouble(s);
    }

    /** A time in a recording: seconds, or a step's start ("s20") or note ("w20"), with an offset ("s20+1.5"). */
    static double sourceTime(final Source src, final String s) {
        final char c = s.charAt(0);
        if (c != 's' && c != 'w') return Double.parseDouble(s);
        int end = 1;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        final int step = Integer.parseInt(s.substring(1, end));
        final double off = end < s.length() ? Double.parseDouble(s.substring(end)) : 0;
        return src.stepTime(step, c == 'w') + off;
    }

    static void parse(final File f) throws Exception {
        Shot shot = null;
        for (String line : Files.readAllLines(f.toPath())) {
            final int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            final String[] p = line.split("\\s+");
            final Map<String, List<double[]>> keys = shot != null ? shot.keys : globalKeys;
            final Map<String, String> set = shot != null ? shot.set : globalSet;
            switch (p[0]) {
                case "source" -> {
                    final Source s = sources.computeIfAbsent(p[1], Source::new);
                    if (shot != null) shot.src = s;
                    else defaultSource = s;
                }
                case "out" -> out = new File(p[1]);
                case "stilldir" -> stillDir = new File(p[1]);
                case "size" -> {
                    W = Integer.parseInt(p[1]);
                    H = Integer.parseInt(p[2]);
                }
                case "fps" -> FPS = Double.parseDouble(p[1]);
                case "bpm" -> BPM = Double.parseDouble(p[1]);
                case "duration" -> DURATION = editTime(p[1]);
                case "shot" -> {
                    shot = new Shot();
                    shot.t0 = editTime(p[1]);
                    shot.t1 = editTime(p[2]);
                    shot.name = p.length > 3 ? p[3] : "shot" + shots.size();
                    shots.add(shot);
                }
                case "end" -> shot = null;
                case "src" -> {
                    // Straight: the span plays across the whole shot.
                    shot.pendingRemap.add(new String[] { "0", p[1] });
                    shot.pendingRemap.add(new String[] { "@end", p[2] });
                }
                case "remap" -> shot.pendingRemap.add(new String[] { p[1], p[2] });
                case "key" -> keys.computeIfAbsent(p[1], k -> new ArrayList<>())
                    .add(new double[] { editTime(p[2]), Double.parseDouble(p[3]) });
                case "set" -> set.put(p[1], p[2]);
                case "still" -> stills.add(editTime(p[1]));
                case "caption", "captiontop", "badge" -> captions.add(
                    new Caption(
                        editTime(p[1]),
                        editTime(p[2]),
                        line.substring(line.indexOf(p[2], line.indexOf(p[1]) + p[1].length()) + p[2].length())
                            .trim(),
                        p[0].equals("badge"),
                        p[0].equals("captiontop")));
                case "title" -> title = line.substring(5)
                    .trim();
                default -> throw new IllegalArgumentException("unknown line: " + line);
            }
        }
        for (final Source s : sources.values()) s.read();
        for (final Shot s : shots) {
            if (s.src == null) s.src = defaultSource;
            for (final String[] r : s.pendingRemap) s.remap.add(
                new double[] { r[0].equals("@end") ? s.t1 - s.t0 : editTime(r[0]), sourceTime(s.src, r[1]) });
            s.remap.sort((a, b) -> Double.compare(a[0], b[0]));
            for (final List<double[]> k : s.keys.values()) k.sort((a, b) -> Double.compare(a[0], b[0]));
        }
        for (final List<double[]> k : globalKeys.values()) k.sort((a, b) -> Double.compare(a[0], b[0]));
    }

    static Shot shotAt(final double t) {
        for (final Shot s : shots) if (t >= s.t0 && t < s.t1) return s;
        return shots.isEmpty() ? null : t < shots.get(0).t0 ? shots.get(0) : shots.get(shots.size() - 1);
    }

    /** A keyed value: the shot's (in its own time), else the edit's, else the default. */
    static double key(final Shot s, final String name, final double t, final double dflt) {
        final boolean linear = s != null && num(s, "linear", 0) > 0;
        if (s != null && s.keys.containsKey(name)) return keyed(s.keys.get(name), t - s.t0, linear);
        if (globalKeys.containsKey(name)) return keyed(globalKeys.get(name), t, false);
        return num(s, name, dflt);
    }

    static double num(final Shot s, final String k, final double d) {
        String v = s != null ? s.set.get(k) : null;
        if (v == null) v = globalSet.get(k);
        return v == null ? d : Double.parseDouble(v);
    }

    /** Smootherstep between keys, so every key is a gentle stop and start. */
    static double keyed(final List<double[]> k, final double t, final boolean linear) {
        if (t <= k.get(0)[0]) return k.get(0)[1];
        for (int i = 1; i < k.size(); i++) {
            final double[] a = k.get(i - 1), b = k.get(i);
            final double u = (t - a[0]) / Math.max(1e-9, b[0] - a[0]);
            if (t <= b[0]) return a[1] + (b[1] - a[1]) * (linear ? u : smoother(u));
        }
        return k.get(k.size() - 1)[1];
    }

    static double smoother(final double u) {
        final double x = Math.max(0, Math.min(1, u));
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    static double smooth(final double e0, final double e1, final double x) {
        final double u = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return u * u * (3 - 2 * u);
    }

    /** A shot's time to its recording's: a monotone cubic through its remap points, so ramps have no corners. */
    static double remapAt(final Shot s, final double local) {
        final List<double[]> r = s.remap;
        final int n = r.size();
        if (n == 0) return local;
        if (n == 1) return r.get(0)[1] + local - r.get(0)[0];
        final double[] xs = new double[n], ys = new double[n], m = new double[n], d = new double[n - 1];
        for (int i = 0; i < n; i++) {
            xs[i] = r.get(i)[0];
            ys[i] = r.get(i)[1];
        }
        for (int i = 0; i < n - 1; i++) d[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) {
                m[i] = m[i + 1] = 0;
                continue;
            }
            final double a = m[i] / d[i], b = m[i + 1] / d[i], q = a * a + b * b;
            if (q > 9) {
                final double tau = 3 / Math.sqrt(q);
                m[i] = tau * a * d[i];
                m[i + 1] = tau * b * d[i];
            }
        }
        if (local <= xs[0]) return ys[0] + (local - xs[0]) * m[0];
        if (local >= xs[n - 1]) return ys[n - 1] + (local - xs[n - 1]) * m[n - 1];
        int i = 0;
        while (local > xs[i + 1]) i++;
        final double h = xs[i + 1] - xs[i], u = (local - xs[i]) / h;
        final double h00 = 2 * u * u * u - 3 * u * u + 1, h10 = u * u * u - 2 * u * u + u,
            h01 = -2 * u * u * u + 3 * u * u, h11 = u * u * u - u * u;
        return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1];
    }

    // endregion

    // region Recordings

    static final class Source {

        final File dir;
        double[] time, px, py;
        boolean[] on;
        int[] step;
        char[] state;
        int count, sw, sh;
        final Map<Integer, int[]> cache = new LinkedHashMap<>(32, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(final Map.Entry<Integer, int[]> e) {
                return size() > 16;
            }
        };

        Source(final String path) {
            dir = new File(path);
        }

        void read() throws Exception {
            if (dir.isFile()) {
                // A still picture (the plan's own, from the Share key's Screenshot): one frame, always.
                final BufferedImage img = ImageIO.read(dir);
                sw = img.getWidth();
                sh = img.getHeight();
                count = 1;
                time = new double[] { 0 };
                px = new double[] { sw / 2.0 };
                py = new double[] { sh / 2.0 };
                on = new boolean[] { false };
                step = new int[] { 0 };
                state = new char[] { '-' };
                System.out.printf(Locale.ROOT, "%s: a still %dx%d%n", dir, sw, sh);
                return;
            }
            final BufferedImage first = ImageIO.read(new File(dir, "f00000.jpg"));
            sw = first.getWidth();
            sh = first.getHeight();
            int n = 0;
            while (new File(dir, String.format("f%05d.jpg", n)).isFile()) n++;
            count = n;
            time = new double[n];
            px = new double[n];
            py = new double[n];
            on = new boolean[n];
            step = new int[n];
            state = new char[n];
            for (int i = 0; i < n; i++) time[i] = i / 60.0;
            double guiW = sw, guiH = sh, t0 = Double.NaN;
            final File csv = new File(dir, "frames.csv");
            if (csv.isFile()) for (final String line : Files.readAllLines(csv.toPath())) {
                final String[] p = line.split(line.startsWith("#") ? "\\s+" : ",");
                if (line.startsWith("# gui")) {
                    guiW = Double.parseDouble(p[2]);
                    guiH = Double.parseDouble(p[3]);
                }
                if (line.startsWith("#")) continue;
                final int i = Integer.parseInt(p[0]);
                if (i >= n) continue;
                if (Double.isNaN(t0)) t0 = Double.parseDouble(p[1]);
                time[i] = (Double.parseDouble(p[1]) - t0) / 1000.0;
                px[i] = Double.parseDouble(p[2]) * sw / guiW;
                py[i] = Double.parseDouble(p[3]) * sh / guiH;
                on[i] = !p[4].equals("0");
                if (p.length > 6) {
                    step[i] = Integer.parseInt(p[5]);
                    state[i] = p[6].charAt(0);
                }
            }
            System.out.printf(Locale.ROOT, "%s: %d frames %dx%d, %.1f s%n", dir, n, sw, sh, time[n - 1]);
        }

        /** When a step started playing, or (note) when its note showed. */
        double stepTime(final int s, final boolean note) {
            for (int i = 0; i < count; i++) if (step[i] == s && (note ? state[i] == 'w' : state[i] != 'w')) return time[i];
            for (int i = 0; i < count; i++) if (step[i] == s) return time[i];
            throw new IllegalArgumentException("no step " + s + " in " + dir);
        }

        /** The frame at or before a time, plus how far toward the next. */
        double frameAt(final double t) {
            if (t <= time[0]) return 0;
            if (t >= time[count - 1]) return count - 1;
            int lo = 0, hi = count - 1;
            while (hi - lo > 1) {
                final int mid = (lo + hi) >>> 1;
                if (time[mid] <= t) lo = mid;
                else hi = mid;
            }
            return lo + (t - time[lo]) / Math.max(1e-9, time[hi] - time[lo]);
        }

        int[] frame(final int i) throws Exception {
            synchronized (cache) {
                final int[] c = cache.get(i);
                if (c != null) return c;
            }
            final BufferedImage img = ImageIO.read(dir.isFile() ? dir : new File(dir, String.format("f%05d.jpg", i)));
            final BufferedImage rgb = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics()
                .drawImage(img, 0, 0, null);
            final int[] pxs = ((DataBufferInt) rgb.getRaster()
                .getDataBuffer()).getData();
            synchronized (cache) {
                cache.put(i, pxs);
            }
            return pxs;
        }
    }

    // endregion

    // region The camera

    record Cam(double roll, double yaw, double pitch, double zoom, double cx, double cy, double dist, double barrel,
        int sw, int sh) {}

    /** Per shot: the tour's pointer at each of its frames, smoothed over the edit's time, so the camera drifts after. */
    static void prepare(final Shot s) {
        final int f0 = (int) Math.round(s.t0 * FPS), f1 = (int) Math.round(s.t1 * FPS), n = Math.max(1, f1 - f0);
        final double[] rx = new double[n], ry = new double[n];
        final boolean[] on = new boolean[n];
        for (int k = 0; k < n; k++) {
            final int i = (int) Math.round(s.src.frameAt(remapAt(s, k / FPS)));
            rx[k] = s.src.px[i];
            ry[k] = s.src.py[i];
            on[k] = s.src.on[i];
        }
        final double sigma = num(s, "followsmooth", 0.35) * FPS;
        final int r = (int) Math.ceil(sigma * 3);
        s.pointer = new double[n * 2];
        for (int k = 0; k < n; k++) {
            double sx = 0, sy = 0, swt = 0;
            for (int j = Math.max(0, k - r); j <= Math.min(n - 1, k + r); j++) {
                if (!on[j]) continue;
                final double w = Math.exp(-(j - k) * (j - k) / (2 * sigma * sigma));
                sx += rx[j] * w;
                sy += ry[j] * w;
                swt += w;
            }
            s.pointer[k * 2] = swt > 0 ? sx / swt : s.src.sw / 2.0;
            s.pointer[k * 2 + 1] = swt > 0 ? sy / swt : s.src.sh / 2.0;
        }
        // The least zoom that keeps the recording's edges out of shot: a running max, then eased.
        final double[] need = new double[n];
        for (int k = 0; k < n; k++) {
            if (covers(cam(s, f0 + k, 0))) continue;
            double lo = 0.5, hi = 6;
            for (int it = 0; it < 28; it++) {
                final double mid = (lo + hi) / 2;
                if (covers(cam(s, f0 + k, mid))) hi = mid;
                else lo = mid;
            }
            need[k] = hi * 1.003;
        }
        final int rr = (int) (FPS * 0.4);
        final double[] mx = new double[n];
        for (int k = 0; k < n; k++) {
            double v = 0;
            for (int j = Math.max(0, k - rr); j <= Math.min(n - 1, k + rr); j++) v = Math.max(v, need[j]);
            mx[k] = v;
        }
        s.zoomFloor = new double[n];
        final double sg = FPS / 5;
        for (int k = 0; k < n; k++) {
            double a = 0, wsum = 0;
            for (int j = Math.max(0, k - rr); j <= Math.min(n - 1, k + rr); j++) {
                final double w = Math.exp(-(j - k) * (j - k) / (2 * sg * sg));
                a += mx[j] * w;
                wsum += w;
            }
            s.zoomFloor[k] = Math.max(need[k], a / wsum);
        }
    }

    static double pulse(final Shot s, final double t) {
        final double amt = key(s, "pulse", t, 0);
        if (amt == 0) return 1;
        final double b = beat(), ph = ((t % b) + b) % b;
        return 1 + amt * Math.exp(-ph / 0.09);
    }

    static Cam cam(final Shot s, final int f, final double floor) {
        final double t = f / FPS;
        final int k = Math.max(0, Math.min(s.pointer.length / 2 - 1, f - (int) Math.round(s.t0 * FPS)));
        final double follow = key(s, "follow", t, 0);
        double cx = key(s, "cx", t, 0.5) * s.src.sw, cy = key(s, "cy", t, 0.5) * s.src.sh;
        if (follow > 0) {
            final double ox = key(s, "followdx", t, 0) * s.src.sw, oy = key(s, "followdy", t, 0) * s.src.sh;
            cx += (s.pointer[k * 2] + ox - cx) * follow;
            cy += (s.pointer[k * 2 + 1] + oy - cy) * follow;
        }
        final double local = t - s.t0, punch = 1 + num(s, "punch", 0) * Math.exp(-local / 0.16);
        final double zoom = key(s, "zoom", t, 1.3) * punch * pulse(s, t);
        return new Cam(
            Math.toRadians(key(s, "roll", t, 0)),
            Math.toRadians(key(s, "yaw", t, 0)),
            Math.toRadians(key(s, "pitch", t, 0)),
            Math.max(floor, zoom),
            cx,
            cy,
            key(s, "dist", t, 2.4),
            num(s, "barrel", 0.0),
            s.src.sw,
            s.src.sh);
    }

    /** The plane's projection, recording pixel to output pixel. */
    static double[][] forward(final Cam c) {
        final double cr = Math.cos(c.roll), sr = Math.sin(c.roll), cp = Math.cos(c.pitch), sp = Math.sin(c.pitch),
            cyw = Math.cos(c.yaw), syw = Math.sin(c.yaw);
        final double[][] rz = { { cr, -sr, 0 }, { sr, cr, 0 }, { 0, 0, 1 } };
        final double[][] rx = { { 1, 0, 0 }, { 0, cp, -sp }, { 0, sp, cp } };
        final double[][] ry = { { cyw, 0, syw }, { 0, 1, 0 }, { -syw, 0, cyw } };
        final double[][] rot = mul(rz, mul(rx, ry));
        final double d = c.dist, fl = c.zoom * H * d;
        final double[][] k = { { fl, 0, W / 2.0 }, { 0, fl, H / 2.0 }, { 0, 0, 1 } };
        final double[][] ext = { { rot[0][0], rot[0][1], 0 }, { rot[1][0], rot[1][1], 0 },
            { rot[2][0], rot[2][1], d } };
        final double sc = 1.0 / c.sh;
        final double[][] a = { { sc, 0, -c.cx * sc }, { 0, sc, -c.cy * sc }, { 0, 0, 1 } };
        return mul(k, mul(ext, a));
    }

    static double[] flat(final double[][] m) {
        return new double[] { m[0][0], m[0][1], m[0][2], m[1][0], m[1][1], m[1][2], m[2][0], m[2][1], m[2][2] };
    }

    static double[][] mul(final double[][] a, final double[][] b) {
        final double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return r;
    }

    static double[][] inv(final double[][] m) {
        final double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0],
            h = m[2][1], i = m[2][2];
        final double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        final double det = a * A + b * B + c * C;
        return new double[][] { { A / det, -(b * i - c * h) / det, (b * f - c * e) / det },
            { B / det, (a * i - c * g) / det, -(a * f - c * d) / det },
            { C / det, -(a * h - b * g) / det, (a * e - b * d) / det } };
    }

    /** Where an output point samples the recording, barrel first. */
    static void map(final double[] m, final double barrel, final double u, final double v, final double[] xy) {
        double uu = u, vv = v;
        if (barrel != 0) {
            final double nx = (u - W / 2.0) / (H / 2.0), ny = (v - H / 2.0) / (H / 2.0),
                k = 1 + barrel * (nx * nx + ny * ny);
            uu = W / 2.0 + nx * k * H / 2.0;
            vv = H / 2.0 + ny * k * H / 2.0;
        }
        final double x = m[0] * uu + m[1] * vv + m[2], y = m[3] * uu + m[4] * vv + m[5], w = m[6] * uu + m[7] * vv + m[8];
        xy[0] = x / w;
        xy[1] = y / w;
    }

    static boolean covers(final Cam c) {
        final double[] m = flat(inv(forward(c))), xy = new double[2];
        for (int i = 0; i <= 32; i++) {
            final double q = i / 32.0;
            for (final double[] p : new double[][] { { q * W, 0 }, { q * W, H }, { 0, q * H }, { W, q * H } }) {
                map(m, c.barrel, p[0], p[1], xy);
                if (xy[0] < 1 || xy[1] < 1 || xy[0] > c.sw - 2 || xy[1] > c.sh - 2) return false;
            }
        }
        return true;
    }

    /** Where a recording point lands on the output (no barrel): for keeping the pointer in focus. */
    static double[] project(final Cam c, final double x, final double y) {
        final double[][] m = forward(c);
        final double u = m[0][0] * x + m[0][1] * y + m[0][2], v = m[1][0] * x + m[1][1] * y + m[1][2],
            w = m[2][0] * x + m[2][1] * y + m[2][2];
        return new double[] { u / w, v / w };
    }

    // endregion

    // region Drawing a frame

    static final float[] LIN = new float[256];
    static {
        for (int i = 0; i < 256; i++) LIN[i] = (float) Math.pow(i / 255.0, 2.2);
    }

    static float toSrgb(final float v) {
        return (float) Math.pow(Math.max(0, v), 1 / 2.2);
    }

    static float[] warp(final Cam c, final int[] a, final int[] b, final float mix) {
        if (b == null && c.sw == W && c.sh == H && c.roll == 0 && c.yaw == 0 && c.pitch == 0 && c.barrel == 0
            && Math.abs(c.zoom - 1) < 1e-9 && Math.abs(c.cx - W / 2.0) < 1e-6 && Math.abs(c.cy - H / 2.0) < 1e-6) {
            final float[] o = new float[W * H * 3];
            for (int p = 0; p < a.length; p++) {
                o[p * 3] = LIN[a[p] >> 16 & 0xFF];
                o[p * 3 + 1] = LIN[a[p] >> 8 & 0xFF];
                o[p * 3 + 2] = LIN[a[p] & 0xFF];
            }
            return o;
        }
        final double[] m = flat(inv(forward(c)));
        final float[] o = new float[W * H * 3];
        final double[][] ss = { { -0.125, -0.375 }, { 0.375, -0.125 }, { 0.125, 0.375 }, { -0.375, 0.125 } };
        IntStream.range(0, H)
            .parallel()
            .forEach(y -> {
                final double[] xy = new double[2];
                final float[] tmp = new float[3];
                for (int x = 0; x < W; x++) {
                    float r = 0, g = 0, bl = 0;
                    for (final double[] s : ss) {
                        map(m, c.barrel, x + 0.5 + s[0], y + 0.5 + s[1], xy);
                        sample(a, c.sw, c.sh, xy[0] - 0.5, xy[1] - 0.5, tmp);
                        if (b != null) {
                            final float r0 = tmp[0], g0 = tmp[1], b0 = tmp[2];
                            sample(b, c.sw, c.sh, xy[0] - 0.5, xy[1] - 0.5, tmp);
                            tmp[0] = r0 + (tmp[0] - r0) * mix;
                            tmp[1] = g0 + (tmp[1] - g0) * mix;
                            tmp[2] = b0 + (tmp[2] - b0) * mix;
                        }
                        r += tmp[0];
                        g += tmp[1];
                        bl += tmp[2];
                    }
                    final int p = (y * W + x) * 3;
                    o[p] = r / 4;
                    o[p + 1] = g / 4;
                    o[p + 2] = bl / 4;
                }
            });
        return o;
    }

    static void sample(final int[] px, final int sw, final int sh, final double fx, final double fy, final float[] out) {
        final int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        final float tx = (float) (fx - x0), ty = (float) (fy - y0);
        final int xa = clamp(x0, sw), xb = clamp(x0 + 1, sw), ya = clamp(y0, sh), yb = clamp(y0 + 1, sh);
        final int p00 = px[ya * sw + xa], p10 = px[ya * sw + xb], p01 = px[yb * sw + xa], p11 = px[yb * sw + xb];
        for (int ch = 0; ch < 3; ch++) {
            final int sh2 = 16 - ch * 8;
            final float c00 = LIN[p00 >> sh2 & 0xFF], c10 = LIN[p10 >> sh2 & 0xFF], c01 = LIN[p01 >> sh2 & 0xFF],
                c11 = LIN[p11 >> sh2 & 0xFF];
            final float top = c00 + (c10 - c00) * tx, bot = c01 + (c11 - c01) * tx;
            out[ch] = top + (bot - top) * ty;
        }
    }

    static int clamp(final int v, final int n) {
        return v < 0 ? 0 : v >= n ? n - 1 : v;
    }

    /** Three box passes each way: close to a Gaussian. */
    static float[] blur(final float[] src, final int w, final int h, final int radius) {
        final float[] a = src.clone(), b = new float[src.length];
        for (int pass = 0; pass < 3; pass++) {
            IntStream.range(0, h)
                .parallel()
                .forEach(y -> boxRow(a, b, w, y, radius));
            IntStream.range(0, w)
                .parallel()
                .forEach(x -> boxCol(b, a, w, h, x, radius));
        }
        return a;
    }

    static void boxRow(final float[] in, final float[] o, final int w, final int y, final int r) {
        final float norm = 1f / (2 * r + 1);
        for (int ch = 0; ch < 3; ch++) {
            float s = 0;
            for (int i = -r; i <= r; i++) s += in[(y * w + Math.max(0, Math.min(w - 1, i))) * 3 + ch];
            for (int x = 0; x < w; x++) {
                o[(y * w + x) * 3 + ch] = s * norm;
                s += in[(y * w + Math.min(w - 1, x + r + 1)) * 3 + ch] - in[(y * w + Math.max(0, x - r)) * 3 + ch];
            }
        }
    }

    static void boxCol(final float[] in, final float[] o, final int w, final int h, final int x, final int r) {
        final float norm = 1f / (2 * r + 1);
        for (int ch = 0; ch < 3; ch++) {
            float s = 0;
            for (int i = -r; i <= r; i++) s += in[(Math.max(0, Math.min(h - 1, i)) * w + x) * 3 + ch];
            for (int y = 0; y < h; y++) {
                o[(y * w + x) * 3 + ch] = s * norm;
                s += in[(Math.min(h - 1, y + r + 1) * w + x) * 3 + ch] - in[(Math.max(0, y - r) * w + x) * 3 + ch];
            }
        }
    }

    static float[] half(final float[] src, final int w, final int h) {
        final int hw = w / 2, hh = h / 2;
        final float[] o = new float[hw * hh * 3];
        IntStream.range(0, hh)
            .parallel()
            .forEach(y -> {
                for (int x = 0; x < hw; x++)
                    for (int ch = 0; ch < 3; ch++) {
                        final int a = ((2 * y) * w + 2 * x) * 3 + ch, b = ((2 * y + 1) * w + 2 * x) * 3 + ch;
                        o[(y * hw + x) * 3 + ch] = (src[a] + src[a + 3] + src[b] + src[b + 3]) / 4;
                    }
            });
        return o;
    }

    static float bilerp(final float[] s, final int w, final int h, final double fx, final double fy, final int ch) {
        final double x = Math.max(0, Math.min(w - 1.001, fx)), y = Math.max(0, Math.min(h - 1.001, fy));
        final int x0 = (int) x, y0 = (int) y;
        final float tx = (float) (x - x0), ty = (float) (y - y0);
        final float a = s[(y0 * w + x0) * 3 + ch], b = s[(y0 * w + x0 + 1) * 3 + ch],
            c = s[((y0 + 1) * w + x0) * 3 + ch], d = s[((y0 + 1) * w + x0 + 1) * 3 + ch];
        final float top = a + (b - a) * tx, bot = c + (d - c) * tx;
        return top + (bot - top) * ty;
    }

    /** Frames {@code a} to {@code b} of a source, averaged. */
    static int[] average(final Source src, final int a, final int b) throws Exception {
        final int n = b - a + 1, size = src.sw * src.sh;
        final int[][] fs = new int[n][];
        for (int i = 0; i < n; i++) fs[i] = src.frame(a + i);
        final int[] o = new int[size];
        IntStream.range(0, src.sh)
            .parallel()
            .forEach(y -> {
                for (int x = y * src.sw, end = x + src.sw; x < end; x++) {
                    int r = 0, g = 0, bl = 0;
                    for (final int[] f : fs) {
                        r += f[x] >> 16 & 0xFF;
                        g += f[x] >> 8 & 0xFF;
                        bl += f[x] & 0xFF;
                    }
                    o[x] = r / n << 16 | g / n << 8 | bl / n;
                }
            });
        return o;
    }

    /** One shot's frame at an edit frame, graded, as display values (0 to 1). */
    static float[] shade(final Shot s, final int f) throws Exception {
        final double t = f / FPS;
        final int k = Math.max(0, Math.min(s.zoomFloor.length - 1, f - (int) Math.round(s.t0 * FPS)));
        final Cam cam = cam(s, f, s.zoomFloor[k]);
        final double fi = s.src.frameAt(remapAt(s, t - s.t0));
        final int i0 = (int) Math.floor(fi), i1 = Math.min(s.src.count - 1, i0 + 1);
        final float mix = (float) (fi - i0);
        // Blended, a frame between two; else the nearer one (blending fast motion doubles it up). With a shutter, a
        // sped-up shot shows every frame its output frame spans, averaged: a time-lapse's motion blur.
        final boolean blending = num(s, "blendframes", 1) > 0, blend = blending && mix > 0.02f && mix < 0.98f;
        final int last = (int) Math.round(s.src.frameAt(remapAt(s, t - s.t0 + num(s, "shutter", 0) / FPS)));
        final float[] sharp = last > i0 + 1 ? warp(cam, average(s.src, i0, Math.min(last, i0 + 12)), null, 0)
            : warp(cam, s.src.frame(mix >= (blending ? 0.98f : 0.5f) ? i1 : i0), blend ? s.src.frame(i1) : null, mix);

        final int hw = W / 2, hh = H / 2;
        final float[] halfImg = half(sharp, W, H);
        final float[] blurS = blur(halfImg, hw, hh, (int) Math.max(1, Math.round(num(s, "dofsmall", 1)))),
            blurL = blur(halfImg, hw, hh, (int) Math.max(1, Math.round(num(s, "doflarge", 3))));
        final double thr = num(s, "bloomthreshold", 0.55), bloom = key(s, "bloom", t, 0.08);
        final float[] bright = new float[halfImg.length];
        for (int p = 0; p < halfImg.length; p += 3) {
            final float l = 0.2126f * halfImg[p] + 0.7152f * halfImg[p + 1] + 0.0722f * halfImg[p + 2];
            final float q = (float) Math.max(0, (l - thr) / (1 - thr));
            bright[p] = halfImg[p] * q;
            bright[p + 1] = halfImg[p + 1] * q;
            bright[p + 2] = halfImg[p + 2] * q;
        }
        final int br = (int) num(s, "bloomradius", 8);
        final float[] bloomA = blur(bright, hw, hh, br), bloomB = blur(bloomA, hw, hh, br * 3);

        // Focus: keyed, or after the pointer (where it lands on screen).
        double fx = key(s, "focusx", t, 0.5) * W, fy = key(s, "focusy", t, 0.5) * H;
        final double ff = key(s, "focusfollow", t, 0);
        if (ff > 0) {
            final double[] p = project(cam, s.pointer[k * 2], s.pointer[k * 2 + 1]);
            fx += (p[0] - fx) * ff;
            fy += (p[1] - fy) * ff;
        }
        final double fr = key(s, "focusr", t, 0.6), fall = key(s, "focusfall", t, 0.6), all = key(s, "blur", t, 0);
        // A cut lands a touch bright and settles, on the beat it is cut to.
        final double exposure = key(s, "exposure", t, 1) * (1 + num(s, "cutflash", 0) * Math.exp(-(t - s.t0) / 0.12)),
            darken = key(s, "darken", t, 0);
        // Neutral by default: the planner's own colours, a light vignette, a little grain against banding.
        final double vig = num(s, "vignette", 0.12), sat = key(s, "saturation", t, 1), ca = num(s, "aberration", 0);
        final double[] lift = { num(s, "liftr", 0), num(s, "liftg", 0), num(s, "liftb", 0) };
        final double[] gain = { num(s, "gainr", 1), num(s, "gaing", 1), num(s, "gainb", 1) };
        final double grain = num(s, "grain", 0.008);
        final float[] o = new float[W * H * 3];
        final double cfx = fx, cfy = fy;
        IntStream.range(0, H)
            .parallel()
            .forEach(y -> {
                long seed = ((long) f * 7919L + y * 104729L) * 6364136223846793005L + 1442695040888963407L;
                final float[] px = new float[3];
                for (int x = 0; x < W; x++) {
                    final double nx = (x - W / 2.0) / (H / 2.0), ny = (y - H / 2.0) / (H / 2.0), r2 = nx * nx + ny * ny;
                    final double dx = (x - cfx) / H, dy = (y - cfy) / H;
                    final double m = Math.max(all, smooth(fr, fr + fall, Math.sqrt(dx * dx * 0.6 + dy * dy)));
                    for (int ch = 0; ch < 3; ch++) {
                        // Chromatic aberration: red a touch out, blue a touch in, from the middle.
                        final double sc = ch == 0 ? 1 + ca * r2 : ch == 2 ? 1 - ca * r2 : 1;
                        final double sx = W / 2.0 + (x + 0.5 - W / 2.0) * sc - 0.5,
                            sy = H / 2.0 + (y + 0.5 - H / 2.0) * sc - 0.5;
                        float v = ch == 1 || ca == 0 ? sharp[(y * W + x) * 3 + ch] : bilerp(sharp, W, H, sx, sy, ch);
                        final double hx = (sx + 0.5) / 2 - 0.5, hy = (sy + 0.5) / 2 - 0.5;
                        if (m > 0.002) {
                            final float a = bilerp(blurS, hw, hh, hx, hy, ch), l = bilerp(blurL, hw, hh, hx, hy, ch);
                            v = m < 0.5 ? v + (a - v) * (float) (m * 2) : a + (l - a) * (float) (m * 2 - 1);
                        }
                        v += (float) bloom
                            * (bilerp(bloomA, hw, hh, hx, hy, ch) * 0.6f + bilerp(bloomB, hw, hh, hx, hy, ch) * 0.8f);
                        px[ch] = v;
                    }
                    float r = toSrgb(px[0]), g = toSrgb(px[1]), b = toSrgb(px[2]);
                    final float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    r = l + (r - l) * (float) sat;
                    g = l + (g - l) * (float) sat;
                    b = l + (b - l) * (float) sat;
                    r = (float) (lift[0] + r * (gain[0] - lift[0]));
                    g = (float) (lift[1] + g * (gain[1] - lift[1]));
                    b = (float) (lift[2] + b * (gain[2] - lift[2]));
                    final double v = (1 - vig * Math.pow(Math.min(1.6, Math.sqrt(r2) / 1.25), 2.6)) * exposure
                        * (1 - darken);
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    final float n = (float) (((seed >>> 40) & 0xFFFF) / 65535.0 - 0.5) * (float) grain;
                    final int p = (y * W + x) * 3;
                    o[p] = (float) (r * v) + n;
                    o[p + 1] = (float) (g * v) + n;
                    o[p + 2] = (float) (b * v) + n;
                }
            });

        return o;
    }

    /**
     * An edit frame: its shot, dissolving from the one before over the shot's {@code dissolve} seconds; then the end
     * card's background (the board's canvas and dots) and the card itself; then {@code master}, for the last fade.
     */
    static byte[] render(final int f) throws Exception {
        final double t = f / FPS;
        final Shot s = shotAt(t);
        final float[] o = shade(s, f);
        final int index = shots.indexOf(s);
        final double dissolve = num(s, "dissolve", 0);
        if (index > 0 && dissolve > 0 && t - s.t0 < dissolve) {
            final float[] prev = shade(shots.get(index - 1), f);
            final float m = (float) smoother((t - s.t0) / dissolve);
            for (int p = 0; p < o.length; p++) o[p] = prev[p] + (o[p] - prev[p]) * m;
        }
        final double bg = key(s, "endbg", t, 0), card = key(s, "endcard", t, 0), master = key(s, "master", t, 1);
        if (bg > 0.001) endBackground(o, bg);
        if (card > 0.001) endCard(o, card);
        for (final Caption c : captions) if (t >= c.t0() - 0.01 && t <= c.t1() + 0.4) caption(o, c, t);
        final byte[] bytes = new byte[W * H * 3];
        for (int p = 0; p < o.length; p++)
            bytes[p] = (byte) Math.max(0, Math.min(255, Math.round(o[p] * master * 255)));
        return bytes;
    }

    // The board's canvas and its dots (Hyb.CANVAS, Hyb.CANVAS_DOT), as the planner draws them at the GUI scale.
    static final float[] CANVAS = { 0x14 / 255f, 0x14 / 255f, 0x14 / 255f }, DOT = { 0x26 / 255f, 0x28 / 255f, 0x2D / 255f };

    static void endBackground(final float[] o, final double a) {
        final float m = (float) smoother(a);
        final int step = (int) num(null, "dotstep", 20), size = (int) num(null, "dotsize", 2);
        final int ox = (W / 2) % step, oy = (H / 2) % step;
        IntStream.range(0, H)
            .parallel()
            .forEach(y -> {
                for (int x = 0; x < W; x++) {
                    final float[] c = Math.floorMod(x - ox, step) < size && Math.floorMod(y - oy, step) < size ? DOT
                        : CANVAS;
                    final int p = (y * W + x) * 3;
                    for (int ch = 0; ch < 3; ch++) o[p + ch] += (c[ch] - o[p + ch]) * m;
                }
            });
    }

    // region The game's font: its glyph sheet drawn pixel for pixel, with the game's shadow

    static BufferedImage glyphs, logo;
    static final int[] GLYPH_W = new int[256];

    static void loadArt() throws Exception {
        glyphs = ImageIO.read(
            new File(globalSet.getOrDefault("font", "build/resources/patchedMc/assets/minecraft/textures/font/ascii.png")));
        final int cell = glyphs.getWidth() / 16;
        for (int c = 0; c < 256; c++) {
            int last = -1;
            for (int x = 0; x < cell; x++)
                for (int y = 0; y < cell; y++)
                    if ((glyphs.getRGB((c % 16) * cell + x, (c / 16) * cell + y) >>> 24) != 0) last = Math.max(last, x);
            // As the game measures them: up to the last lit column, and a column of space.
            GLYPH_W[c] = c == ' ' ? 4 : (last + 1) * 8 / cell + 1;
        }
        final String l = globalSet.get("logo");
        if (l != null) logo = ImageIO.read(new File(l));
    }

    static int textWidth(final String s) {
        int w = 0;
        for (final char c : s.toCharArray()) w += GLYPH_W[c & 0xFF];
        return w;
    }

    /** Text as the game draws it: a shadow one pixel down and right at a quarter of the colour, then the text. */
    static void text(final Graphics2D g, final String s, final int x, final int y, final int scale, final int rgb) {
        glyphRun(g, s, x + scale, y + scale, scale, new Color((rgb & 0xFCFCFC) >> 2));
        glyphRun(g, s, x, y, scale, new Color(rgb));
    }

    static void glyphRun(final Graphics2D g, final String s, int x, final int y, final int scale, final Color c) {
        final int cell = glyphs.getWidth() / 16;
        g.setColor(c);
        for (final char ch : s.toCharArray()) {
            final int gx = (ch & 0xFF) % 16 * cell, gy = (ch & 0xFF) / 16 * cell;
            for (int py = 0; py < cell; py++)
                for (int px = 0; px < cell; px++)
                    if ((glyphs.getRGB(gx + px, gy + py) >>> 24) != 0)
                        g.fillRect(x + px * 8 / cell * scale, y + py * 8 / cell * scale, scale, scale);
            x += GLYPH_W[ch & 0xFF] * scale;
        }
    }

    // endregion

    /**
     * A caption: the game's font on a plate of the board's own dark, low on the left, easing up and in, then out; a
     * badge (a time-lapse's speed) small at the top right. Words between asterisks are the brand's cyan.
     */
    static void caption(final float[] o, final Caption c, final double t) {
        final double in = smooth(c.t0(), c.t0() + 0.35, t), outA = 1 - smooth(c.t1(), c.t1() + 0.35, t);
        final float alpha = (float) Math.min(in, outA);
        if (alpha <= 0.001f) return;
        final int scale = c.badge() ? (int) num(null, "badgescale", 3) : (int) num(null, "captionscale", 4);
        final String plain = c.text()
            .replace("*", "");
        final int tw = textWidth(plain) * scale, th = 8 * scale, padX = 6 * scale, padY = 4 * scale;
        final int rise = (int) Math.round((1 - smoother((t - c.t0()) / 0.35)) * 10);
        final int x = c.badge() ? W - (int) num(null, "badgemargin", 72) - tw - 2 * padX : (int) num(null, "captionx", 96);
        final int y = c.badge() ? (int) num(null, "badgemargin", 72)
            : c.top() ? (int) num(null, "captiontopy", 110) + rise : H - (int) num(null, "captiony", 150) - th + rise;
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x14, 0x14, 0x14, 214));
        g.fillRect(x, y - padY, tw + 2 * padX, th + 2 * padY);
        g.setColor(new Color(0x22D3EE));
        g.fillRect(x, y - padY, Math.max(2, scale / 2 + 1), th + 2 * padY);
        int cx = x + padX;
        boolean accent = false;
        for (final String run : c.text()
            .split("\\*", -1)) {
            if (!run.isEmpty()) {
                text(g, run, cx, y, scale, accent ? 0x22D3EE : c.badge() ? 0x9A9CA4 : 0xE8E9EE);
                cx += textWidth(run) * scale;
            }
            accent = !accent;
        }
        g.dispose();
        final int[] px = ((DataBufferInt) img.getRaster()
            .getDataBuffer()).getData();
        for (int q = 0; q < px.length; q++) {
            final int argb = px[q];
            final float a = (argb >>> 24) / 255f * alpha;
            if (a <= 0) continue;
            final int i = q * 3;
            o[i] = o[i] * (1 - a) + ((argb >> 16 & 0xFF) / 255f) * a;
            o[i + 1] = o[i + 1] * (1 - a) + ((argb >> 8 & 0xFF) / 255f) * a;
            o[i + 2] = o[i + 2] * (1 - a) + ((argb & 0xFF) / 255f) * a;
        }
    }

    /** The end card: the logo (when the shot file names one) over the name in the game's font, fading up together. */
    static void endCard(final float[] o, final double p) {
        final float alpha = (float) smooth(0, 0.7, p);
        final int rise = (int) Math.round((1 - smoother(p)) * 8);
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        final int scale = (int) num(null, "wordscale", 7), tw = textWidth(title) * scale, th = 8 * scale;
        final int ls = (int) num(null, "logoscale", 2), gap = (int) num(null, "logogap", 36);
        final int lw = logo == null ? 0 : logo.getWidth() * ls, lh = logo == null ? 0 : logo.getHeight() * ls;
        final int total = lh + (logo == null ? 0 : gap) + th, top = (H - total) / 2 + rise;
        if (logo != null) g.drawImage(logo, (W - lw) / 2, top, lw, lh, null);
        text(g, title, (W - tw) / 2, top + total - th, scale,
            Integer.parseInt(globalSet.getOrDefault("wordcolor", "22D3EE"), 16));
        g.dispose();
        final int[] px = ((DataBufferInt) img.getRaster()
            .getDataBuffer()).getData();
        for (int q = 0; q < px.length; q++) {
            final int argb = px[q];
            final float a = (argb >>> 24) / 255f * alpha;
            if (a <= 0) continue;
            final int i = q * 3;
            o[i] = o[i] * (1 - a) + ((argb >> 16 & 0xFF) / 255f) * a;
            o[i + 1] = o[i + 1] * (1 - a) + ((argb >> 8 & 0xFF) / 255f) * a;
            o[i + 2] = o[i + 2] * (1 - a) + ((argb & 0xFF) / 255f) * a;
        }
    }

    // endregion

    static void still(final double t) throws Exception {
        final int f = Math.min((int) Math.round(DURATION * FPS) - 1, (int) Math.round(t * FPS));
        final byte[] b = render(f);
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        final int[] px = ((DataBufferInt) img.getRaster()
            .getDataBuffer()).getData();
        for (int p = 0; p < px.length; p++)
            px[p] = (b[p * 3] & 0xFF) << 16 | (b[p * 3 + 1] & 0xFF) << 8 | b[p * 3 + 2] & 0xFF;
        final File file = new File(stillDir, String.format(Locale.ROOT, "still-%05.2f.jpg", t));
        ImageIO.write(img, "jpg", file);
        System.out.println("still " + file + " (" + shotAt(t).name + ")");
    }

    public static void main(final String[] args) throws Exception {
        parse(new File(args[0]));
        loadArt();
        for (final Shot s : shots) prepare(s);
        if (stillDir != null) stillDir.mkdirs();
        if (args.length > 1 && args[1].equals("--every")) {
            final double every = Double.parseDouble(args[2]);
            for (double t = 0; t < DURATION; t += every) still(t);
            return;
        }
        for (final double t : stills) still(t);
        if (args.length > 1 && args[1].equals("--stills")) return;
        out.getAbsoluteFile()
            .getParentFile()
            .mkdirs();
        final List<String> cmd = new ArrayList<>(
            List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "rgb24",
                "-s",
                W + "x" + H,
                "-r",
                String.valueOf(FPS),
                "-i",
                "-"));
        final String audio = globalSet.get("audio");
        if (audio != null) cmd.addAll(List.of("-i", audio));
        cmd.addAll(
            List.of(
                "-c:v",
                "libx264",
                "-preset",
                "slow",
                "-crf",
                globalSet.getOrDefault("crf", "15"),
                "-tune",
                "film",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart"));
        if (audio != null) cmd.addAll(
            List.of(
                "-af",
                String.format(
                    Locale.ROOT,
                    "volume=%s,afade=t=out:st=%.3f:d=%.3f",
                    globalSet.getOrDefault("audiogain", "0dB"),
                    DURATION - num(null, "audiofade", 1.6),
                    num(null, "audiofade", 1.6)),
                "-ar",
                "48000",
                "-c:a",
                "aac",
                "-b:a",
                "256k",
                "-t",
                String.valueOf(DURATION)));
        cmd.add(out.getPath());
        final Process p = new ProcessBuilder(cmd).inheritIO()
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start();
        final long started = System.nanoTime();
        final int frames = (int) Math.round(DURATION * FPS);
        try (OutputStream os = p.getOutputStream()) {
            for (int f = 0; f < frames; f++) {
                os.write(render(f));
                if (f % 60 == 0) System.out.printf(Locale.ROOT, "frame %d/%d (%s)%n", f, frames, shotAt(f / FPS).name);
            }
        }
        System.out.printf(
            Locale.ROOT,
            "ffmpeg exit %d, %.1f s, %s%n",
            p.waitFor(),
            (System.nanoTime() - started) / 1e9,
            out.getAbsolutePath());
    }
}
