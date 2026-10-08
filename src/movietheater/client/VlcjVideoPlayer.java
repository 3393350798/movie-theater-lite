package movietheater.client;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class VlcjVideoPlayer implements VideoPlayerBackend {
    private final boolean available;
    private final JComponent component;
    private final Object embeddedComponent;
    private final Object mediaPlayer;
    private final String initError;
    private Path currentFile;
    private Consumer<String> statusListener = message -> {
    };

    VlcjVideoPlayer() {
        boolean ok = false;
        JComponent createdComponent;
        Object createdEmbeddedComponent = null;
        Object createdMediaPlayer = null;
        String capturedInitError = null;
        try {
            Path vlcHome = VlcRuntime.findCompatibleVlcHome();
            if (vlcHome == null) {
                Path detected = VlcRuntime.findAnyVlcHome();
                String problem = VlcRuntime.compatibilityProblem(detected);
                throw new IllegalStateException(problem == null ? "未检测到可用 VLC" : problem);
            }
            configureVlcDiscoveryPath();
            Class<?> componentClass = Class.forName("uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent");
            createdEmbeddedComponent = componentClass.getConstructor().newInstance();
            if (createdEmbeddedComponent instanceof JComponent) {
                createdComponent = (JComponent) createdEmbeddedComponent;
            } else {
                throw new IllegalStateException("VLCJ component is not a Swing component");
            }
            Object mediaPlayerFactory = componentClass.getMethod("mediaPlayerFactory").invoke(createdEmbeddedComponent);
            try {
                Object application = mediaPlayerFactory.getClass().getMethod("application").invoke(mediaPlayerFactory);
                application.getClass().getMethod("setUserAgent", String.class, String.class)
                        .invoke(application, "MovieTheaterLite", "MovieTheaterLite");
            } catch (Throwable ignored) {
                // User agent is optional.
            }
            createdMediaPlayer = componentClass.getMethod("mediaPlayer").invoke(createdEmbeddedComponent);
            createdComponent.setBackground(Color.BLACK);
            createdComponent.setBorder(BorderFactory.createEtchedBorder());
            createdComponent.setPreferredSize(new Dimension(640, 360));
            createdComponent.setMinimumSize(new Dimension(360, 220));
            ok = true;
        } catch (Throwable ex) {
            capturedInitError = shortMessage(ex);
            JPanel fallback = new JPanel(new BorderLayout());
            JLabel label = new JLabel("<html><center>当前未加载 VLCJ/VLC 内嵌播放器。<br>"
                    + escapeHtml(capturedInitError) + "<br>"
                    + "未加载时可继续使用 JavaFX 或系统播放器作为备用播放方式。</center></html>",
                    SwingConstants.CENTER);
            fallback.add(label, BorderLayout.CENTER);
            fallback.setBorder(BorderFactory.createEtchedBorder());
            fallback.setPreferredSize(new Dimension(640, 360));
            fallback.setMinimumSize(new Dimension(360, 220));
            createdComponent = fallback;
        }
        this.available = ok;
        this.component = createdComponent;
        this.embeddedComponent = createdEmbeddedComponent;
        this.mediaPlayer = createdMediaPlayer;
        this.initError = capturedInitError;
    }

    @Override
    public JComponent getComponent() {
        return component;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    String initError() {
        return initError;
    }

    @Override
    public String backendName() {
        return "VLCJ";
    }

    @Override
    public Path getCurrentFile() {
        return currentFile;
    }

    @Override
    public boolean isCurrentFileForVideo(int videoId) {
        return currentFile != null && currentFile.getFileName().toString().startsWith(videoId + "_");
    }

    @Override
    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener == null ? message -> {
        } : statusListener;
    }

    @Override
    public long readDurationMs(Path file, long timeoutMs) {
        return -1;
    }

    @Override
    public void load(Path file) {
        if (!available || file == null) {
            return;
        }
        currentFile = file;
        publishStatus("正在用 VLC 内嵌播放器加载视频：" + file.getFileName());
        if (!waitUntilDisplayable(5000)) {
            publishStatus("VLCJ 加载视频失败：播放器界面尚未显示完成，请稍后重新进入房间或重新加载视频。");
            return;
        }
        try {
            if (playWithFallbackLocations(file)) {
                publishStatus("VLCJ 已加载视频：" + file.getFileName());
            } else {
                publishStatus("VLCJ 加载视频失败：" + playerDebugState());
            }
        } catch (Throwable ex) {
            publishStatus("VLCJ 加载视频失败：" + shortMessage(ex));
        }
    }

    @Override
    public void play() {
        if (!available) {
            return;
        }
        try {
            call(call(mediaPlayer, "controls"), "play");
        } catch (Throwable ex) {
            publishStatus("VLCJ 播放失败：" + shortMessage(ex));
        }
    }

    @Override
    public void pause() {
        if (!available) {
            return;
        }
        try {
            Object controls = call(mediaPlayer, "controls");
            try {
                controls.getClass().getMethod("setPause", boolean.class).invoke(controls, true);
            } catch (NoSuchMethodException ex) {
                if (isPlaying()) {
                    call(controls, "pause");
                }
            }
        } catch (Throwable ex) {
            publishStatus("VLCJ 暂停失败：" + shortMessage(ex));
        }
    }

    @Override
    public void seek(long positionMs) {
        if (!available) {
            return;
        }
        try {
            waitForSeekable(2500);
            Object controls = call(mediaPlayer, "controls");
            try {
                controls.getClass().getMethod("setTime", long.class).invoke(controls, positionMs);
            } catch (NoSuchMethodException e) {
                controls.getClass().getMethod("setTime", int.class).invoke(controls, (int) Math.min(Integer.MAX_VALUE, positionMs));
            }
        } catch (Throwable ex) {
            publishStatus("VLCJ 调整进度失败：" + shortMessage(ex));
        }
    }

    @Override
    public long currentPositionMs() {
        if (!available) {
            return -1;
        }
        try {
            Object status = call(mediaPlayer, "status");
            Object value = call(status, "time");
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @Override
    public long durationMs() {
        if (!available) {
            return -1;
        }
        try {
            Object status = call(mediaPlayer, "status");
            Object value = call(status, "length");
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @Override
    public boolean isPlaying() {
        if (!available) {
            return false;
        }
        try {
            Object status = call(mediaPlayer, "status");
            return Boolean.TRUE.equals(call(status, "isPlaying"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public double playbackRate() {
        if (!available) {
            return 1.0;
        }
        try {
            Object status = call(mediaPlayer, "status");
            Object value = call(status, "rate");
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return 1.0;
    }

    @Override
    public void setPlaybackRate(double rate) {
        if (!available) {
            return;
        }
        double safeRate = Math.max(0.25, Math.min(4.0, rate));
        try {
            Object controls = call(mediaPlayer, "controls");
            try {
                controls.getClass().getMethod("setRate", float.class).invoke(controls, (float) safeRate);
            } catch (NoSuchMethodException e) {
                controls.getClass().getMethod("setRate", double.class).invoke(controls, safeRate);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void dispose() {
        if (!available || embeddedComponent == null) {
            return;
        }
        try {
            call(call(mediaPlayer, "controls"), "stop");
        } catch (Throwable ignored) {
        }
        try {
            call(embeddedComponent, "release");
        } catch (Throwable ignored) {
        }
    }

    private static void configureVlcDiscoveryPath() {
        VlcRuntime.configureJnaLibraryPath();
    }

    private static String mediaLocation(Path file) {
        return file.toAbsolutePath().toUri().toString();
    }

    private boolean playWithFallbackLocations(Path file) throws Exception {
        Throwable lastError = null;
        for (String location : mediaLocations(file)) {
            try {
                stopQuietly();
                boolean accepted = playLocation(location);
                if (accepted && waitForMediaStarted(2500)) {
                    return true;
                }
            } catch (Throwable ex) {
                lastError = ex;
            }
        }
        if (lastError != null) {
            publishStatus("VLCJ 加载视频失败：" + shortMessage(lastError));
        }
        return false;
    }

    private Set<String> mediaLocations(Path file) {
        Set<String> locations = new LinkedHashSet<>();
        Path absolute = file.toAbsolutePath();
        locations.add(absolute.toString());
        try {
            locations.add(absolute.toRealPath().toString());
        } catch (Exception ignored) {
        }
        locations.add(mediaLocation(file));
        return locations;
    }

    private boolean playLocation(String location) throws Exception {
        Object mediaApi = call(mediaPlayer, "media");
        try {
            Method play = mediaApi.getClass().getMethod("play", String.class, String[].class);
            return !Boolean.FALSE.equals(play.invoke(mediaApi, new Object[]{location, new String[0]}));
        } catch (NoSuchMethodException ex) {
            Method start = mediaApi.getClass().getMethod("start", String.class, String[].class);
            return !Boolean.FALSE.equals(start.invoke(mediaApi, new Object[]{location, new String[0]}));
        }
    }

    private boolean waitForMediaStarted(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hasStartedMedia()) {
                return true;
            }
            if (isEndedWithoutLength()) {
                return false;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return hasStartedMedia();
    }

    private void waitForSeekable(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Object status = call(mediaPlayer, "status");
                if (Boolean.TRUE.equals(call(status, "isSeekable"))) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean hasStartedMedia() {
        try {
            Object status = call(mediaPlayer, "status");
            String state = String.valueOf(call(status, "state"));
            boolean playing = Boolean.TRUE.equals(call(status, "isPlaying"));
            long length = numberValue(call(status, "length"));
            long time = numberValue(call(status, "time"));
            long videoOutputs = numberValue(call(status, "videoOutputs"));
            return playing || length > 0 || time > 0 || videoOutputs > 0
                    || "PLAYING".equals(state) || "PAUSED".equals(state)
                    || "OPENING".equals(state) || "BUFFERING".equals(state);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isEndedWithoutLength() {
        try {
            Object status = call(mediaPlayer, "status");
            String state = String.valueOf(call(status, "state"));
            long length = numberValue(call(status, "length"));
            long time = numberValue(call(status, "time"));
            return "ENDED".equals(state) && length <= 0 && time <= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void stopQuietly() {
        try {
            call(call(mediaPlayer, "controls"), "stop");
        } catch (Throwable ignored) {
        }
    }

    private boolean waitUntilDisplayable(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (component.isDisplayable() && component.isShowing()) {
                return true;
            }
            try {
                SwingUtilities.invokeAndWait(() -> {
                    component.addNotify();
                    component.validate();
                });
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return component.isDisplayable();
    }

    private String playerDebugState() {
        return debugStatus();
    }

    String debugStatus() {
        try {
            Object status = call(mediaPlayer, "status");
            Object state = call(status, "state");
            Object playable = call(status, "isPlayable");
            Object playing = call(status, "isPlaying");
            Object seekable = call(status, "isSeekable");
            Object length = call(status, "length");
            Object time = call(status, "time");
            Object videoOutputs = call(status, "videoOutputs");
            return "state=" + state
                    + ", playing=" + playing
                    + ", playable=" + playable
                    + ", seekable=" + seekable
                    + ", length=" + length
                    + ", time=" + time
                    + ", videoOutputs=" + videoOutputs;
        } catch (Throwable ex) {
            return "请确认本机 VLC 可以播放该文件；诊断信息：" + shortMessage(ex);
        }
    }

    private long numberValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private String shortMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void publishStatus(String message) {
        try {
            statusListener.accept(message);
        } catch (RuntimeException ignored) {
        }
    }
}
