package movietheater.client;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class EmbeddedVideoPlayer implements VideoPlayerBackend {
    private final boolean available;
    private final JComponent component;
    private final Class<?> platformClass;
    private final Class<?> mediaClass;
    private final Class<?> mediaPlayerClass;
    private final Class<?> mediaViewClass;
    private Object mediaPlayer;
    private Path currentFile;
    private Consumer<String> statusListener = message -> {
    };

    EmbeddedVideoPlayer() {
        JComponent createdComponent;
        Class<?> createdPlatform = null;
        Class<?> createdMedia = null;
        Class<?> createdMediaPlayer = null;
        Class<?> createdMediaView = null;
        boolean ok = false;
        try {
            createdPlatform = Class.forName("javafx.application.Platform");
            createdMedia = Class.forName("javafx.scene.media.Media");
            createdMediaPlayer = Class.forName("javafx.scene.media.MediaPlayer");
            createdMediaView = Class.forName("javafx.scene.media.MediaView");
            Class<?> jfxPanelClass = Class.forName("javafx.embed.swing.JFXPanel");
            Object jfxPanel = jfxPanelClass.getConstructor().newInstance();
            createdComponent = (JComponent) jfxPanel;
            createdComponent.setBackground(Color.BLACK);
            createdComponent.setBorder(BorderFactory.createEtchedBorder());
            createdComponent.setPreferredSize(new Dimension(640, 360));
            createdComponent.setMinimumSize(new Dimension(360, 220));
            createdPlatform.getMethod("setImplicitExit", boolean.class).invoke(null, false);
            ok = true;
        } catch (Throwable ex) {
            JPanel fallback = new JPanel(new BorderLayout());
            JLabel label = new JLabel("<html><center>当前未加载 JavaFX MediaPlayer。<br>"
                    + "请把 JavaFX SDK 的 lib 目录放到项目 javafx-lib，或设置 JAVAFX_LIB。<br>"
                    + "未加载时仍可上传、建房、同步进度和用系统播放器打开视频。</center></html>",
                    SwingConstants.CENTER);
            fallback.add(label, BorderLayout.CENTER);
            fallback.setBorder(BorderFactory.createEtchedBorder());
            fallback.setPreferredSize(new Dimension(640, 360));
            fallback.setMinimumSize(new Dimension(360, 220));
            createdComponent = fallback;
        }
        this.available = ok;
        this.component = createdComponent;
        this.platformClass = createdPlatform;
        this.mediaClass = createdMedia;
        this.mediaPlayerClass = createdMediaPlayer;
        this.mediaViewClass = createdMediaView;
    }

    @Override
    public JComponent getComponent() {
        return component;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String backendName() {
        return "JavaFX";
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
        if (!available || file == null) {
            return -1;
        }
        AtomicLong result = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);
        runFx(() -> {
            final Object[] playerHolder = new Object[1];
            try {
                Object media = mediaClass.getConstructor(String.class).newInstance(file.toUri().toString());
                Object player = mediaPlayerClass.getConstructor(mediaClass).newInstance(media);
                playerHolder[0] = player;
                mediaPlayerClass.getMethod("setOnReady", Runnable.class).invoke(player, (Runnable) () -> {
                    try {
                        result.set(readPlayerTotalDurationMs(player));
                    } finally {
                        disposePlayer(player);
                        latch.countDown();
                    }
                });
                mediaPlayerClass.getMethod("setOnError", Runnable.class).invoke(player, (Runnable) () -> {
                    disposePlayer(player);
                    latch.countDown();
                });
            } catch (Throwable ex) {
                if (playerHolder[0] != null) {
                    disposePlayer(playerHolder[0]);
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS)) {
                publishStatus("读取视频时长超时，将使用默认时长。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @Override
    public void load(Path file) {
        if (!available || file == null) {
            return;
        }
        currentFile = file;
        publishStatus("正在加载视频：" + file.getFileName());
        runFx(() -> {
            try {
                disposeOnFx();
                Object media = mediaClass.getConstructor(String.class).newInstance(file.toUri().toString());
                mediaPlayer = mediaPlayerClass.getConstructor(mediaClass).newInstance(media);
                registerMediaCallbacks(media, mediaPlayer);
                Object mediaView = mediaViewClass.getConstructor(mediaPlayerClass).newInstance(mediaPlayer);
                mediaViewClass.getMethod("setPreserveRatio", boolean.class).invoke(mediaView, true);
                try {
                    mediaViewClass.getMethod("setSmooth", boolean.class).invoke(mediaView, true);
                } catch (NoSuchMethodException ignored) {
                    // JavaFX versions before/after this method can still render without smoothing.
                }

                Class<?> stackPaneClass = Class.forName("javafx.scene.layout.StackPane");
                Object root = stackPaneClass.getConstructor().newInstance();
                stackPaneClass.getMethod("setStyle", String.class).invoke(root, "-fx-background-color: black;");
                Object children = stackPaneClass.getMethod("getChildren").invoke(root);
                children.getClass().getMethod("add", Object.class).invoke(children, mediaView);
                bindMediaViewSize(mediaView, root);

                Class<?> parentClass = Class.forName("javafx.scene.Parent");
                Class<?> sceneClass = Class.forName("javafx.scene.Scene");
                Constructor<?> sceneConstructor = sceneClass.getConstructor(parentClass, double.class, double.class);
                Object scene = sceneConstructor.newInstance(root, 640.0, 360.0);
                component.getClass().getMethod("setScene", sceneClass).invoke(component, scene);
            } catch (Throwable ex) {
                publishStatus("视频加载失败：" + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }

    @Override
    public void play() {
        if (!available) {
            return;
        }
        runFx(() -> {
            try {
                if (mediaPlayer != null) {
                    mediaPlayerClass.getMethod("play").invoke(mediaPlayer);
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        });
    }

    @Override
    public void pause() {
        if (!available) {
            return;
        }
        runFx(() -> {
            try {
                if (mediaPlayer != null) {
                    mediaPlayerClass.getMethod("pause").invoke(mediaPlayer);
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        });
    }

    @Override
    public void seek(long positionMs) {
        if (!available) {
            return;
        }
        runFx(() -> {
            try {
                if (mediaPlayer != null) {
                    Class<?> durationClass = Class.forName("javafx.util.Duration");
                    Object duration = durationClass.getMethod("millis", double.class).invoke(null, (double) positionMs);
                    mediaPlayerClass.getMethod("seek", durationClass).invoke(mediaPlayer, duration);
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        });
    }

    @Override
    public long currentPositionMs() {
        if (!available || mediaPlayer == null) {
            return -1;
        }
        AtomicLong result = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);
        runFx(() -> {
            try {
                Object duration = mediaPlayerClass.getMethod("getCurrentTime").invoke(mediaPlayer);
                double millis = (double) duration.getClass().getMethod("toMillis").invoke(duration);
                result.set((long) millis);
            } catch (Throwable ignored) {
                result.set(-1);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @Override
    public long durationMs() {
        if (!available || mediaPlayer == null) {
            return -1;
        }
        AtomicLong result = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);
        runFx(() -> {
            try {
                Object duration = mediaPlayerClass.getMethod("getTotalDuration").invoke(mediaPlayer);
                double millis = (double) duration.getClass().getMethod("toMillis").invoke(duration);
                result.set((long) millis);
            } catch (Throwable ignored) {
                result.set(-1);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @Override
    public boolean isPlaying() {
        if (!available || mediaPlayer == null) {
            return false;
        }
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);
        runFx(() -> {
            try {
                Object status = mediaPlayerClass.getMethod("getStatus").invoke(mediaPlayer);
                result.set("PLAYING".equals(String.valueOf(status)));
            } catch (Throwable ignored) {
                result.set(false);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @Override
    public double playbackRate() {
        if (!available || mediaPlayer == null) {
            return 1.0;
        }
        AtomicReference<Double> result = new AtomicReference<>(1.0);
        CountDownLatch latch = new CountDownLatch(1);
        runFx(() -> {
            try {
                Object value = mediaPlayerClass.getMethod("getRate").invoke(mediaPlayer);
                if (value instanceof Number) {
                    result.set(((Number) value).doubleValue());
                }
            } catch (Throwable ignored) {
                result.set(1.0);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @Override
    public void setPlaybackRate(double rate) {
        if (!available) {
            return;
        }
        double safeRate = Math.max(0.25, Math.min(4.0, rate));
        runFx(() -> {
            try {
                if (mediaPlayer != null) {
                    mediaPlayerClass.getMethod("setRate", double.class).invoke(mediaPlayer, safeRate);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void dispose() {
        if (!available) {
            return;
        }
        runFx(this::disposeOnFx);
    }

    private void disposeOnFx() {
        try {
            if (mediaPlayer != null) {
                disposePlayer(mediaPlayer);
                mediaPlayer = null;
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }

    private void bindMediaViewSize(Object mediaView, Object root) {
        try {
            Class<?> observableValueClass = Class.forName("javafx.beans.value.ObservableValue");
            Object fitWidth = mediaViewClass.getMethod("fitWidthProperty").invoke(mediaView);
            Object fitHeight = mediaViewClass.getMethod("fitHeightProperty").invoke(mediaView);
            Object width = root.getClass().getMethod("widthProperty").invoke(root);
            Object height = root.getClass().getMethod("heightProperty").invoke(root);
            fitWidth.getClass().getMethod("bind", observableValueClass).invoke(fitWidth, width);
            fitHeight.getClass().getMethod("bind", observableValueClass).invoke(fitHeight, height);
        } catch (Throwable ignored) {
            // Natural media size is still usable if property binding is unavailable.
        }
    }

    private long readPlayerTotalDurationMs(Object player) {
        try {
            Object duration = mediaPlayerClass.getMethod("getTotalDuration").invoke(player);
            double millis = (double) duration.getClass().getMethod("toMillis").invoke(duration);
            if (Double.isFinite(millis) && millis > 0) {
                return (long) millis;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void disposePlayer(Object player) {
        try {
            mediaPlayerClass.getMethod("stop").invoke(player);
        } catch (Throwable ignored) {
        }
        try {
            mediaPlayerClass.getMethod("dispose").invoke(player);
        } catch (Throwable ignored) {
        }
    }

    private void registerMediaCallbacks(Object media, Object player) {
        try {
            mediaPlayerClass.getMethod("setOnReady", Runnable.class).invoke(player, (Runnable) () -> {
                int width = intMediaValue(media, "getWidth");
                int height = intMediaValue(media, "getHeight");
                long durationMs = durationMediaValue(media, "getDuration");
                if (width <= 0 || height <= 0) {
                    publishStatus("已加载声音，但 JavaFX 未检测到视频画面；可改用系统播放器作为备用播放。");
                } else {
                    publishStatus("视频已加载：" + width + "x" + height + "，时长 " + movietheater.common.Protocol.formatDuration(durationMs));
                }
            });
        } catch (Throwable ignored) {
            // Playback can continue without ready callbacks.
        }
        try {
            mediaPlayerClass.getMethod("setOnError", Runnable.class).invoke(player,
                    (Runnable) () -> publishStatus("视频播放错误：" + errorMessage(player, mediaPlayerClass)));
        } catch (Throwable ignored) {
            // Error callbacks are only used for user-facing diagnostics.
        }
        try {
            mediaClass.getMethod("setOnError", Runnable.class).invoke(media,
                    (Runnable) () -> publishStatus("视频文件错误：" + errorMessage(media, mediaClass)));
        } catch (Throwable ignored) {
            // Error callbacks are only used for user-facing diagnostics.
        }
    }

    private int intMediaValue(Object media, String method) {
        try {
            Object value = mediaClass.getMethod(method).invoke(media);
            if (value instanceof Number) {
                return (int) Math.round(((Number) value).doubleValue());
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private long durationMediaValue(Object media, String method) {
        try {
            Object duration = mediaClass.getMethod(method).invoke(media);
            double millis = (double) duration.getClass().getMethod("toMillis").invoke(duration);
            if (Double.isFinite(millis) && millis > 0) {
                return (long) millis;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private String errorMessage(Object target, Class<?> targetClass) {
        try {
            Object error = targetClass.getMethod("getError").invoke(target);
            return error == null ? "未知错误" : error.toString();
        } catch (Throwable ignored) {
            return "未知错误";
        }
    }

    private void publishStatus(String message) {
        try {
            statusListener.accept(message);
        } catch (RuntimeException ignored) {
        }
    }

    private void runFx(Runnable action) {
        if (!available) {
            return;
        }
        try {
            Method runLater = platformClass.getMethod("runLater", Runnable.class);
            runLater.invoke(null, action);
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }
}
