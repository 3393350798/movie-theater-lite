package movietheater.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class VideoMetadataReader {
    private static final int MAX_DEPTH = 8;

    private VideoMetadataReader() {
    }

    public static long readDurationMs(Path file) {
        if (file == null || !looksLikeMp4(file)) {
            return -1;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            return scanBoxes(channel, 0, channel.size(), 0);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static boolean looksLikeMp4(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov");
    }

    private static long scanBoxes(SeekableByteChannel channel, long start, long end, int depth) throws IOException {
        if (depth > MAX_DEPTH || start < 0 || end <= start) {
            return -1;
        }
        long bestDuration = -1;
        long pos = start;
        while (pos + 8 <= end) {
            channel.position(pos);
            long size32 = readUInt32(channel);
            String type = readType(channel);
            long headerSize = 8;
            long boxSize = size32;
            if (size32 == 1) {
                if (pos + 16 > end) {
                    break;
                }
                boxSize = readUInt64(channel);
                headerSize = 16;
            } else if (size32 == 0) {
                boxSize = end - pos;
            }
            if (boxSize < headerSize || pos + boxSize > end) {
                break;
            }

            long contentStart = pos + headerSize;
            long contentEnd = pos + boxSize;
            if ("mvhd".equals(type) || "mdhd".equals(type)) {
                long duration = readTimeBoxDurationMs(channel, contentStart, contentEnd);
                if (duration > 0) {
                    if ("mvhd".equals(type)) {
                        return duration;
                    }
                    bestDuration = Math.max(bestDuration, duration);
                }
            } else if (isContainer(type)) {
                long nestedDuration = scanBoxes(channel, contentStart, contentEnd, depth + 1);
                if (nestedDuration > 0) {
                    bestDuration = Math.max(bestDuration, nestedDuration);
                }
            }
            pos += boxSize;
        }
        return bestDuration;
    }

    private static boolean isContainer(String type) {
        return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type)
                || "minf".equals(type) || "stbl".equals(type) || "edts".equals(type);
    }

    private static long readTimeBoxDurationMs(SeekableByteChannel channel, long start, long end) throws IOException {
        if (end - start < 16) {
            return -1;
        }
        channel.position(start);
        int versionAndFlags = readInt(channel);
        int version = (versionAndFlags >>> 24) & 0xff;
        long timescale;
        long duration;
        if (version == 1) {
            if (end - start < 32) {
                return -1;
            }
            readUInt64(channel);
            readUInt64(channel);
            timescale = readUInt32(channel);
            duration = readUInt64(channel);
        } else {
            readUInt32(channel);
            readUInt32(channel);
            timescale = readUInt32(channel);
            duration = readUInt32(channel);
        }
        if (timescale <= 0 || duration <= 0) {
            return -1;
        }
        return duration * 1000L / timescale;
    }

    private static int readInt(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        readFully(channel, buffer);
        buffer.flip();
        return buffer.getInt();
    }

    private static long readUInt32(SeekableByteChannel channel) throws IOException {
        return Integer.toUnsignedLong(readInt(channel));
    }

    private static long readUInt64(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        readFully(channel, buffer);
        buffer.flip();
        return buffer.getLong();
    }

    private static String readType(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        readFully(channel, buffer);
        return new String(buffer.array(), StandardCharsets.US_ASCII);
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("unexpected end of file");
            }
        }
    }
}
