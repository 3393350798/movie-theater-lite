package movietheater.client;

import movietheater.common.Protocol;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class VideoMetadataCheck {
    private VideoMetadataCheck() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: VideoMetadataCheck <video-file>");
            return;
        }
        Path file = Paths.get(args[0]);
        long durationMs = VideoMetadataReader.readDurationMs(file);
        if (durationMs > 0) {
            System.out.println("duration=" + Protocol.formatDuration(durationMs) + " (" + durationMs + " ms)");
        } else {
            System.out.println("duration=unknown");
        }
    }
}
