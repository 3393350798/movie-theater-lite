package movietheater.client;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class VlcjDiagnostic {
    private VlcjDiagnostic() {
    }

    public static void main(String[] args) throws Exception {
        Path anyVlc = VlcRuntime.findAnyVlcHome();
        Path compatibleVlc = VlcRuntime.findCompatibleVlcHome();
        System.out.println("Java: " + VlcRuntime.javaArchitectureLabel());
        System.out.println("Detected VLC: " + (anyVlc == null ? "not found" : anyVlc));
        System.out.println("Compatible VLC: " + (compatibleVlc == null ? "not found" : compatibleVlc));
        String problem = VlcRuntime.compatibilityProblem(anyVlc);
        if (problem != null) {
            System.out.println("Problem: " + problem.replace('\n', ' '));
        }
        if (compatibleVlc == null) {
            System.out.println("Result: VLCJ cannot be used until Java and VLC bitness match.");
            return;
        }

        VlcjVideoPlayer player = new VlcjVideoPlayer();
        player.setStatusListener(message -> System.out.println("Player: " + message));
        System.out.println("VLCJ available: " + player.isAvailable());
        if (!player.isAvailable()) {
            System.out.println("VLCJ init error: " + player.initError());
        }
        Path video = resolveVideo(args);
        if (video != null) {
            System.out.println("Loading: " + video.toAbsolutePath());
            System.out.println("Exists: " + Files.exists(video));
            if (Files.exists(video)) {
                System.out.println("Size: " + Files.size(video));
            }
            JFrame frame = new JFrame("VLCJ Diagnostic");
            SwingUtilities.invokeAndWait(() -> {
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.add(player.getComponent(), BorderLayout.CENTER);
                frame.setSize(800, 450);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
            player.load(video);
            System.out.println("After load: " + player.debugStatus());
            for (int i = 1; i <= 5; i++) {
                Thread.sleep(1000);
                System.out.println("After " + i + "s: " + player.debugStatus());
            }
            System.out.println("Position: " + player.currentPositionMs());
            player.dispose();
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static Path resolveVideo(String[] args) throws Exception {
        if (args.length > 0) {
            return Paths.get(args[0]);
        }
        Path cache = Paths.get("client-cache");
        if (!Files.isDirectory(cache)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cache, "*.mp4")) {
            for (Path file : stream) {
                return file;
            }
        }
        return null;
    }
}
