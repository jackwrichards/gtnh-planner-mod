package com.gtnhplanner.dev;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.tutorial.Pointer;
import com.gtnhplanner.ui.tutorial.Tutorial;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Records the game's own frames for demo videos: {@code call 'record?start=name&fps=24&width=1280'} begins writing
 * JPEG frames to {@code run/client/recordings/<name>/}, {@code call 'record?stop=1'} ends it and reports the count.
 * Frames are read from the back buffer after each drawn frame, so other windows never get in the way; while a screen
 * is open a cursor is drawn where the mouse is, since the system's is not in the frame ({@code cursor=0} leaves it
 * out, for the tour, which draws its own). Frames are dropped, never queued without end, when writing falls behind;
 * {@code threads} and {@code quality} (0 to 1) trade speed for size. Turn them into a video with ffmpeg at the same
 * fps. Stop also writes {@code frames.csv}: per frame its time, where the tour's pointer was and the tour's step, for
 * cutting by step and following the pointer ({@code tools/trailer/}).
 */
public final class DevRecorder {

    static final DevRecorder INSTANCE = new DevRecorder();

    private record Frame(ByteBuffer pixels, int w, int h, int mouseX, int mouseY, boolean cursor, int index) {}

    private volatile File dir;
    private long frameNanos, last;
    private int outWidth;
    private float quality;
    private boolean noCursor;
    private final AtomicInteger index = new AtomicInteger(), dropped = new AtomicInteger();
    private final BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(64);
    private volatile ExecutorService writers;
    private int bufferSize, pool;
    /** frames.csv as it is written: one line per frame kept, on the render thread (stop takes it once writing ends). */
    private volatile StringBuilder log;
    private long started;

    private DevRecorder() {}

    Map<String, Object> start(final String name, final int fps, final int width, final int threads, final float quality,
        final boolean cursor) {
        stop();
        final File d = new File(Minecraft.getMinecraft().mcDataDir, "recordings/" + name);
        d.mkdirs();
        final File[] old = d.listFiles();
        if (old != null) for (final File f : old) f.delete();
        frameNanos = 1_000_000_000L / Math.max(1, fps);
        outWidth = width;
        this.quality = quality;
        noCursor = !cursor;
        index.set(0);
        dropped.set(0);
        free.clear();
        bufferSize = 0;
        writers = Executors.newFixedThreadPool(Math.max(1, threads));
        pool = Math.max(1, threads) + 3;
        last = 0;
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        log = new StringBuilder(
            "# gui " + sr.getScaledWidth()
                + " "
                + sr.getScaledHeight()
                + " display "
                + mc.displayWidth
                + " "
                + mc.displayHeight
                + "\n# index,ms,pointerX,pointerY,driving,step,state\n");
        started = System.nanoTime();
        dir = d;
        return Map.of("recording", d.getAbsolutePath(), "fps", fps);
    }

    Map<String, Object> stop() {
        final File d = dir;
        dir = null;
        final ExecutorService ex = writers;
        writers = null;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(20, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
        final StringBuilder l = log;
        log = null;
        if (d != null && l != null) try {
            Files.write(
                new File(d, "frames.csv").toPath(),
                l.toString()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            return Map
                .of("frames", index.get(), "dropped", dropped.get(), "dir", d.getAbsolutePath(), "csv", e.toString());
        }
        return d == null ? Map.of("recording", false)
            : Map.of("frames", index.get(), "dropped", dropped.get(), "dir", d.getAbsolutePath());
    }

    @SubscribeEvent
    public void onRender(final TickEvent.RenderTickEvent event) {
        // Stop comes from the harness's thread: read both once, and a pool shut down under us drops the frame.
        final File d = dir;
        final ExecutorService ex = writers;
        if (event.phase != TickEvent.Phase.END || d == null || ex == null) return;
        final long now = System.nanoTime();
        if (last != 0 && now - last < frameNanos) return;
        last = last == 0 ? now : last + frameNanos * Math.max(1, (now - last) / frameNanos);
        final Minecraft mc = Minecraft.getMinecraft();
        final int w = mc.displayWidth, h = mc.displayHeight;
        if (bufferSize != w * h * 3) {
            free.clear();
            bufferSize = w * h * 3;
            for (int i = 0; i < pool; i++) free.offer(BufferUtils.createByteBuffer(bufferSize));
        }
        final ByteBuffer buf = free.poll();
        if (buf == null) {
            dropped.incrementAndGet();
            return;
        }
        buf.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
        final Frame f = new Frame(
            buf,
            w,
            h,
            Mouse.getX(),
            Mouse.getY(),
            mc.currentScreen != null && !noCursor,
            index.getAndIncrement());
        final StringBuilder l = log;
        if (l != null) {
            // "step 5 of 26 waiting: ..." -> 5,w
            final String where = Tutorial.where();
            int step = 0;
            char state = '-';
            if (where.startsWith("step ")) {
                final int sp = where.indexOf(' ', 5);
                step = Integer.parseInt(where.substring(5, sp));
                state = where.contains(" waiting") ? 'w' : where.contains(" catching up") ? 'c' : 'p';
            }
            l.append(f.index)
                .append(',')
                .append((now - started) / 1_000_000.0)
                .append(',')
                .append(Pointer.x())
                .append(',')
                .append(Pointer.y())
                .append(',')
                .append(Pointer.driving() ? 1 : 0)
                .append(',')
                .append(step)
                .append(',')
                .append(state)
                .append('\n');
        }
        try {
            ex.execute(() -> write(f, d));
        } catch (final RejectedExecutionException e) {
            free.offer(buf);
        }
    }

    private void write(final Frame f, final File d) {
        try {
            final BufferedImage full = new BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_RGB);
            final int[] px = ((DataBufferInt) full.getRaster()
                .getDataBuffer()).getData();
            final byte[] row = new byte[f.w * 3];
            for (int y = 0; y < f.h; y++) {
                f.pixels.get((f.h - 1 - y) * f.w * 3, row);
                for (int x = 0, p = 0, o = y * f.w; x < f.w; x++, p += 3)
                    px[o + x] = (row[p] & 0xFF) << 16 | (row[p + 1] & 0xFF) << 8 | row[p + 2] & 0xFF;
            }
            free.offer(f.pixels);
            final int ow = Math.min(outWidth, f.w), oh = f.h * ow / f.w;
            BufferedImage out = full;
            if (ow != f.w || f.cursor) {
                out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
                final Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(full, 0, 0, ow, oh, null);
                if (f.cursor) cursor(g, f.mouseX * ow / (float) f.w, (f.h - f.mouseY) * oh / (float) f.h, ow / 1280f);
                g.dispose();
            }
            final ImageWriter jpg = ImageIO.getImageWritersByFormatName("jpg")
                .next();
            final ImageWriteParam p = jpg.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            try (ImageOutputStream os = ImageIO
                .createImageOutputStream(new File(d, String.format("f%05d.jpg", f.index)))) {
                jpg.setOutput(os);
                jpg.write(null, new IIOImage(out, null, null), p);
            } finally {
                jpg.dispose();
            }
        } catch (final Exception e) {
            free.offer(f.pixels);
        }
    }

    /** An arrow pointer at the mouse, white with a dark edge. */
    private static void cursor(final Graphics2D g, final float x, final float y, final float s) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        final int[] px = { 0, 0, 4, 7, 9, 6, 11 }, py = { 0, 16, 12, 18, 17, 11, 11 };
        final Polygon p = new Polygon();
        for (int i = 0; i < px.length; i++)
            p.addPoint(Math.round(x + px[i] * 1.4f * s), Math.round(y + py[i] * 1.4f * s));
        g.setColor(Color.WHITE);
        g.fillPolygon(p);
        g.setColor(new Color(20, 20, 20));
        g.setStroke(new BasicStroke(1.2f * s));
        g.drawPolygon(p);
    }
}
