package movietheater.client;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.function.Consumer;

interface VideoPlayerBackend {
    JComponent getComponent();

    boolean isAvailable();

    String backendName();

    Path getCurrentFile();

    boolean isCurrentFileForVideo(int videoId);

    void setStatusListener(Consumer<String> statusListener);

    long readDurationMs(Path file, long timeoutMs);

    void load(Path file);

    void play();

    void pause();

    void seek(long positionMs);

    long currentPositionMs();

    long durationMs();

    boolean isPlaying();

    double playbackRate();

    void setPlaybackRate(double rate);

    void dispose();
}
