package movietheater.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

final class VlcRuntime {
    private static final String X86 = "x86";
    private static final String X64 = "x64";
    private static final String ARM64 = "arm64";
    private static final String UNKNOWN = "unknown";

    private VlcRuntime() {
    }

    static Path findCompatibleVlcHome() {
        Path env = envVlcHome();
        if (isCompatibleVlcHome(env)) {
            return env.toAbsolutePath();
        }
        for (Path candidate : defaultVlcHomes()) {
            if (isCompatibleVlcHome(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    static Path findAnyVlcHome() {
        Path env = envVlcHome();
        if (hasLibVlc(env)) {
            return env.toAbsolutePath();
        }
        for (Path candidate : defaultVlcHomes()) {
            if (hasLibVlc(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    static void configureJnaLibraryPath() {
        Path vlcHome = findCompatibleVlcHome();
        if (vlcHome != null) {
            System.setProperty("jna.library.path", vlcHome.toString());
        }
    }

    static String javaArchitectureLabel() {
        return architectureLabel(javaArchitectureCode());
    }

    static String compatibilityProblem(Path vlcHome) {
        if (vlcHome == null) {
            return "未检测到 VLC 播放器本体。";
        }
        Path libvlc = libvlcPath(vlcHome);
        if (!Files.exists(libvlc)) {
            return "VLC 安装目录中没有找到 libvlc.dll：" + vlcHome;
        }
        if (!isWindows()) {
            return null;
        }
        String javaArch = javaArchitectureCode();
        String vlcArch = vlcArchitectureCode(vlcHome);
        if (X64.equals(javaArch) && isLikelyX86Install(vlcHome)) {
            return "检测到的是 32 位 VLC 安装目录：" + vlcHome
                    + "\n当前 Java 是 64 位，VLCJ 内嵌播放器要求 Java 和 VLC 位数一致。";
        }
        if (!UNKNOWN.equals(javaArch) && !UNKNOWN.equals(vlcArch) && !javaArch.equals(vlcArch)) {
            return "检测到的 VLC 是 " + architectureLabel(vlcArch)
                    + "，当前 Java 是 " + architectureLabel(javaArch)
                    + "，VLCJ 内嵌播放器要求二者位数一致。";
        }
        return null;
    }

    private static boolean isCompatibleVlcHome(Path vlcHome) {
        if (!hasLibVlc(vlcHome)) {
            return false;
        }
        return compatibilityProblem(vlcHome) == null;
    }

    private static Path envVlcHome() {
        String env = System.getenv("VLC_HOME");
        if (env == null || env.isBlank()) {
            return null;
        }
        return Paths.get(env.trim());
    }

    private static Path[] defaultVlcHomes() {
        if (isWindows()) {
            return new Path[]{
                    Paths.get("C:\\Program Files\\VideoLAN\\VLC"),
                    Paths.get("C:\\Program Files (x86)\\VideoLAN\\VLC")
            };
        }
        return new Path[0];
    }

    private static boolean hasLibVlc(Path vlcHome) {
        return vlcHome != null && Files.exists(libvlcPath(vlcHome));
    }

    private static Path libvlcPath(Path vlcHome) {
        return vlcHome.resolve(isWindows() ? "libvlc.dll" : "libvlc.so");
    }

    private static boolean isLikelyX86Install(Path vlcHome) {
        return vlcHome.toString().toLowerCase(Locale.ROOT).contains("program files (x86)");
    }

    private static String javaArchitectureCode() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return ARM64;
        }
        if (arch.contains("64")) {
            return X64;
        }
        if (arch.contains("86") || arch.contains("32")) {
            return X86;
        }
        String model = System.getProperty("sun.arch.data.model", "");
        if ("64".equals(model)) {
            return X64;
        }
        if ("32".equals(model)) {
            return X86;
        }
        return UNKNOWN;
    }

    private static String vlcArchitectureCode(Path vlcHome) {
        if (!isWindows()) {
            return UNKNOWN;
        }
        int machine = readPeMachine(libvlcPath(vlcHome));
        if (machine == 0x8664) {
            return X64;
        }
        if (machine == 0x014c) {
            return X86;
        }
        if (machine == 0xaa64) {
            return ARM64;
        }
        return UNKNOWN;
    }

    private static int readPeMachine(Path dll) {
        try (RandomAccessFile raf = new RandomAccessFile(dll.toFile(), "r")) {
            if (readLittleEndianUnsignedShort(raf) != 0x5a4d) {
                return -1;
            }
            raf.seek(0x3c);
            int peOffset = readLittleEndianInt(raf);
            if (peOffset < 0) {
                return -1;
            }
            raf.seek(peOffset);
            if (readLittleEndianInt(raf) != 0x00004550) {
                return -1;
            }
            return readLittleEndianUnsignedShort(raf);
        } catch (IOException | RuntimeException ex) {
            return -1;
        }
    }

    private static int readLittleEndianInt(RandomAccessFile raf) throws IOException {
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int b3 = raf.readUnsignedByte();
        int b4 = raf.readUnsignedByte();
        return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    private static int readLittleEndianUnsignedShort(RandomAccessFile raf) throws IOException {
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        return b1 | (b2 << 8);
    }

    private static String architectureLabel(String code) {
        if (X64.equals(code)) {
            return "64 位";
        }
        if (X86.equals(code)) {
            return "32 位";
        }
        if (ARM64.equals(code)) {
            return "ARM64";
        }
        return "未知位数";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
