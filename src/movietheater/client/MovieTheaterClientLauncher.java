package movietheater.client;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MovieTheaterClientLauncher {
    private static final String JAVAFX_VERSION = "17.0.9";
    private static final String VLCJ_VERSION = "4.8.3";
    private static final String JNA_VERSION = "5.14.0";
    private static final String SLF4J_VERSION = "1.7.36";
    private static final String MAVEN_BASE = "https://repo1.maven.org/maven2/org/openjfx";
    private static final String MAVEN_ROOT = "https://repo1.maven.org/maven2";
    private static final String VLC_DOWNLOAD_PAGE = "https://www.videolan.org/vlc/download-windows.html";
    private static final List<String> JAVAFX_ARTIFACTS = Arrays.asList(
            "javafx-base",
            "javafx-graphics",
            "javafx-media",
            "javafx-swing",
            "javafx-controls"
    );
    private static final List<MavenArtifact> VLCJ_ARTIFACTS = Arrays.asList(
            new MavenArtifact("uk/co/caprica", "vlcj", VLCJ_VERSION),
            new MavenArtifact("uk/co/caprica", "vlcj-natives", VLCJ_VERSION),
            new MavenArtifact("net/java/dev/jna", "jna", JNA_VERSION),
            new MavenArtifact("net/java/dev/jna", "jna-platform", JNA_VERSION),
            new MavenArtifact("org/slf4j", "slf4j-api", SLF4J_VERSION),
            new MavenArtifact("org/slf4j", "slf4j-simple", SLF4J_VERSION)
    );

    private MovieTheaterClientLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path vlcjLib = findVlcjLib();
        if (vlcjLib == null) {
            int choice = showConfirm("VLCJ 依赖缺失", "当前客户端未检测到 VLCJ 依赖，无法使用 VLC 内嵌播放器。\n\n"
                    + "是否自动下载 VLCJ、VLCJ-Natives、JNA、SLF4J 依赖到项目 vlcj-lib 目录？\n\n"
                    + "下载完成后仍需要本机已安装 VLC 播放器，或设置 VLC_HOME 指向 VLC 安装目录。");
            if (choice == JOptionPane.YES_OPTION) {
                try {
                    vlcjLib = downloadVlcjLib();
                    showMessage("VLCJ 依赖下载完成。");
                } catch (Exception ex) {
                    showMessage("VLCJ 自动下载失败：\n" + ex.getMessage()
                            + "\n\n客户端会继续尝试 JavaFX 或系统播放器。");
                }
            }
        }
        Path detectedVlcHome = VlcRuntime.findAnyVlcHome();
        Path vlcHome = VlcRuntime.findCompatibleVlcHome();
        boolean vlcReady = vlcjLib != null && vlcHome != null;
        if (vlcjLib != null && vlcHome == null) {
            String problem = VlcRuntime.compatibilityProblem(detectedVlcHome);
            int choice = showConfirm("VLC 播放器不可用", "已检测到 VLCJ Java 依赖，但未检测到可用于内嵌播放的 VLC。\n\n"
                    + "当前 Java：" + VlcRuntime.javaArchitectureLabel() + "\n"
                    + (problem == null ? "未检测到与当前 Java 位数匹配的 VLC。" : problem) + "\n\n"
                    + "请安装与当前 Java 位数一致的 VLC，或设置环境变量 VLC_HOME 指向正确的 VLC 安装目录，例如：\n"
                    + "C:\\Program Files\\VideoLAN\\VLC\n\n"
                    + "是否打开 VideoLAN 官方 VLC 下载页面？");
            if (choice == JOptionPane.YES_OPTION) {
                openVlcDownloadPage();
                showMessage("安装 VLC 后请重新启动客户端。\n\n"
                        + "如果未安装到默认目录，请设置 VLC_HOME 指向 VLC 安装目录。");
            } else {
                showMessage("未安装 VLC 时，客户端会继续尝试 JavaFX 或系统播放器。\n\n"
                        + "官方下载页面：\n" + VLC_DOWNLOAD_PAGE);
            }
        }

        Path fxLib = findJavaFxLib();
        if (!vlcReady && fxLib == null) {
            int choice = showConfirm("JavaFX 播放组件缺失", "当前客户端未检测到 JavaFX 备用播放组件。\n\n"
                    + "是否自动下载 JavaFX 到项目 javafx-lib 目录？\n"
                    + "下载后会保存到项目的 javafx-lib 目录。\n\n"
                    + "如果已准备使用 VLCJ + VLC，可以取消此项。");
            if (choice == JOptionPane.YES_OPTION) {
                try {
                    fxLib = downloadJavaFxLib();
                    showMessage("JavaFX 下载完成。");
                } catch (Exception ex) {
                    showMessage("JavaFX 自动下载失败：\n" + ex.getMessage()
                            + "\n\n将以无内嵌播放模式启动。也可以手动把 JavaFX SDK 的 lib 目录复制到 javafx-lib。");
                }
            }
        }
        launchClient(vlcjLib, fxLib, vlcHome);
    }

    private static Path findVlcjLib() {
        Path local = Paths.get("vlcj-lib");
        if (looksLikeVlcjLib(local)) {
            return local.toAbsolutePath();
        }
        return null;
    }

    private static boolean looksLikeVlcjLib(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return existsGlob(dir, "vlcj-*.jar")
                && existsGlob(dir, "vlcj-natives-*.jar")
                && existsGlob(dir, "jna-*.jar")
                && existsGlob(dir, "jna-platform-*.jar");
    }

    private static Path findJavaFxLib() {
        String env = System.getenv("JAVAFX_LIB");
        if (env != null && !env.isBlank()) {
            Path path = Paths.get(env.trim());
            if (looksLikeJavaFxLib(path)) {
                return path.toAbsolutePath();
            }
        }
        Path local = Paths.get("javafx-lib");
        if (looksLikeJavaFxLib(local)) {
            return local.toAbsolutePath();
        }
        return null;
    }

    private static boolean looksLikeJavaFxLib(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return exists(dir, "javafx.media.jar")
                || exists(dir, "javafx-media-" + JAVAFX_VERSION + ".jar")
                || existsGlob(dir, "javafx-media-*.jar");
    }

    private static boolean exists(Path dir, String fileName) {
        return Files.exists(dir.resolve(fileName));
    }

    private static boolean existsGlob(Path dir, String glob) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static Path downloadJavaFxLib() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new IOException("自动下载目前只支持 Windows 环境。");
        }
        Path libDir = Paths.get("javafx-lib").toAbsolutePath();
        Files.createDirectories(libDir);
        for (String artifact : JAVAFX_ARTIFACTS) {
            downloadArtifact(libDir, artifact, artifact + "-" + JAVAFX_VERSION + ".jar");
            downloadArtifact(libDir, artifact, artifact + "-" + JAVAFX_VERSION + "-win.jar");
        }
        return libDir;
    }

    private static Path downloadVlcjLib() throws IOException {
        Path libDir = Paths.get("vlcj-lib").toAbsolutePath();
        Files.createDirectories(libDir);
        for (MavenArtifact artifact : VLCJ_ARTIFACTS) {
            downloadArtifact(libDir, artifact);
        }
        return libDir;
    }

    private static void downloadArtifact(Path libDir, MavenArtifact artifact) throws IOException {
        String fileName = artifact.artifactId + "-" + artifact.version + ".jar";
        Path target = libDir.resolve(fileName);
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        String url = MAVEN_ROOT + "/" + artifact.groupPath + "/" + artifact.artifactId + "/"
                + artifact.version + "/" + fileName;
        download(url, target);
    }

    private static void downloadArtifact(Path libDir, String artifact, String fileName) throws IOException {
        Path target = libDir.resolve(fileName);
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        String url = MAVEN_BASE + "/" + artifact + "/" + JAVAFX_VERSION + "/" + fileName;
        download(url, target);
    }

    private static void download(String urlText, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "movie-theater-lite-javafx-downloader");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("下载失败 HTTP " + code + "：" + urlText);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
        }
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void launchClient(Path vlcjLib, Path fxLib, Path vlcHome) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        if (fxLib != null) {
            command.add("--module-path");
            command.add(fxLib.toString());
            command.add("--add-modules");
            command.add("javafx.media,javafx.swing,javafx.controls");
        }
        command.add("-cp");
        command.add(buildClientClasspath(vlcjLib));
        command.add("movietheater.client.MovieTheaterClient");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Paths.get("").toAbsolutePath().toFile());
        if (vlcHome != null) {
            builder.environment().put("VLC_HOME", vlcHome.toString());
        }
        builder.inheritIO();
        builder.start();
    }

    private static String buildClientClasspath(Path vlcjLib) {
        String separator = System.getProperty("path.separator");
        String classpath = System.getProperty("java.class.path");
        if (vlcjLib != null) {
            classpath += separator + vlcjLib.toAbsolutePath() + System.getProperty("file.separator") + "*";
        }
        return classpath;
    }

    private static String javaCommand() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path javaExe = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        return Files.exists(javaExe) ? javaExe.toString() : "java";
    }

    private static void openVlcDownloadPage() throws Exception {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            showMessage("当前系统不能自动打开浏览器，请手动访问：\n" + VLC_DOWNLOAD_PAGE);
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(VLC_DOWNLOAD_PAGE));
        } catch (Exception ex) {
            showMessage("打开浏览器失败，请手动访问：\n" + VLC_DOWNLOAD_PAGE + "\n\n原因：\n" + ex.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static int showConfirm(String title, String message) throws Exception {
        final int[] result = new int[1];
        SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showConfirmDialog(
                null, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE));
        return result[0];
    }

    private static void showMessage(String message) throws Exception {
        SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(
                null, message, "提示", JOptionPane.INFORMATION_MESSAGE));
    }

    private static final class MavenArtifact {
        final String groupPath;
        final String artifactId;
        final String version;

        MavenArtifact(String groupPath, String artifactId, String version) {
            this.groupPath = groupPath;
            this.artifactId = artifactId;
            this.version = version;
        }
    }
}
