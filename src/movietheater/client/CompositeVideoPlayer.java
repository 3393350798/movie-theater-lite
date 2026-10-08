package movietheater.client;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.function.Consumer;

final class CompositeVideoPlayer implements VideoPlayerBackend {
    private final VideoPlayerBackend primary;
    private Consumer<String> statusListener = message -> {
    };

    CompositeVideoPlayer() {
        VideoPlayerBackend vlcj = new VlcjVideoPlayer();
        if (vlcj.isAvailable()) {
            primary = vlcj;
        } else {
            primary = vlcj;
        }
        primary.setStatusListener(this::publishStatus);
    }

    @Override
    public JComponent getComponent() {
        return primary.getComponent();
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable();
    }

    @Override
    public String backendName() {
        return primary.backendName();
    }

    @Override
    public Path getCurrentFile() {
        return primary.getCurrentFile();
    }

    @Override
    public boolean isCurrentFileForVideo(int videoId) {
        return primary.isCurrentFileForVideo(videoId);
    }

    @Override
    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener == null ? message -> {
        } : statusListener;
        primary.setStatusListener(this::publishStatus);
        if (primary.isAvailable()) {
            publishStatus("当前内嵌播放器：" + primary.backendName());
        } else if (primary instanceof VlcjVideoPlayer) {
            String error = ((VlcjVideoPlayer) primary).initError();
            publishStatus("VLCJ 初始化失败：" + (error == null ? "未知原因" : error));
        } else {
            publishStatus("未检测到可用内嵌播放器，可使用系统播放器打开视频");
        }
    }

    @Override
    public long readDurationMs(Path file, long timeoutMs) {
        return primary.readDurationMs(file, timeoutMs);
    }

    @Override
    public void load(Path file) {
        primary.load(file);
    }

    @Override
    public void play() {
        primary.play();
    }

    @Override
    public void pause() {
        primary.pause();
    }

    @Override
    public void seek(long positionMs) {
        primary.seek(positionMs);
    }

    @Override
    public long currentPositionMs() {
        return primary.currentPositionMs();
    }

    @Override
    public long durationMs() {
        return primary.durationMs();
    }

    @Override
    public boolean isPlaying() {
        return primary.isPlaying();
    }

    @Override
    public double playbackRate() {
        return primary.playbackRate();
    }

    @Override
    public void setPlaybackRate(double rate) {
        primary.setPlaybackRate(rate);
    }

    @Override
    public void dispose() {
        primary.dispose();
    }

    private void publishStatus(String message) {
        try {
            statusListener.accept(message);
        } catch (RuntimeException ignored) {
        }
    }
}
