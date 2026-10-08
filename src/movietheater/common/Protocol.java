package movietheater.common;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Protocol {
    public static final String LOGIN = "LOGIN";
    public static final String REGISTER = "REGISTER";
    public static final String LIST_ROOMS = "LIST_ROOMS";
    public static final String CREATE_ROOM = "CREATE_ROOM";
    public static final String JOIN_ROOM = "JOIN_ROOM";
    public static final String DELETE_ROOM = "DELETE_ROOM";
    public static final String LIST_ROOM_VIDEOS = "LIST_ROOM_VIDEOS";
    public static final String UPLOAD_VIDEO = "UPLOAD_VIDEO";
    public static final String DELETE_VIDEO = "DELETE_VIDEO";
    public static final String DOWNLOAD_VIDEO = "DOWNLOAD_VIDEO";
    public static final String SELECT_VIDEO = "SELECT_VIDEO";
    public static final String UPDATE_VIDEO_DURATION = "UPDATE_VIDEO_DURATION";
    public static final String LEAVE_ROOM = "LEAVE_ROOM";
    public static final String CONTROL = "CONTROL";
    public static final String CHAT = "CHAT";

    public static final String EVENT_ROOM_UPDATED = "ROOM_UPDATED";
    public static final String EVENT_CHAT = "CHAT_EVENT";

    private Protocol() {
    }

    public static final class Request implements Serializable {
        private static final long serialVersionUID = 1L;

        public long requestId;
        public String type;
        public Map<String, Object> payload = new HashMap<>();

        public Request(long requestId, String type) {
            this.requestId = requestId;
            this.type = type;
        }

        public Request put(String key, Object value) {
            payload.put(key, value);
            return this;
        }
    }

    public static final class Response implements Serializable {
        private static final long serialVersionUID = 1L;

        public long requestId;
        public boolean ok;
        public String message;
        public Map<String, Object> payload = new HashMap<>();

        public Response(long requestId, boolean ok, String message) {
            this.requestId = requestId;
            this.ok = ok;
            this.message = message;
        }

        public Response put(String key, Object value) {
            payload.put(key, value);
            return this;
        }
    }

    public static final class Event implements Serializable {
        private static final long serialVersionUID = 1L;

        public String type;
        public Map<String, Object> payload = new HashMap<>();

        public Event(String type) {
            this.type = type;
        }

        public Event put(String key, Object value) {
            payload.put(key, value);
            return this;
        }
    }

    public static final class User implements Serializable {
        private static final long serialVersionUID = 1L;

        public int id;
        public String username;
        public String nickname;

        public User(int id, String username, String nickname) {
            this.id = id;
            this.username = username;
            this.nickname = nickname;
        }

        @Override
        public String toString() {
            return nickname + "(" + username + ")";
        }
    }

    public static final class RoomSummary implements Serializable {
        private static final long serialVersionUID = 1L;

        public String key;
        public String name;
        public int hostId;
        public String hostName;
        public int maxMembers;
        public long createdAt;
        public int videoCount;
        public String currentVideoName;

        public RoomSummary(String key, String name, int hostId, String hostName,
                           int maxMembers, long createdAt, int videoCount, String currentVideoName) {
            this.key = key;
            this.name = name;
            this.hostId = hostId;
            this.hostName = hostName;
            this.maxMembers = maxMembers;
            this.createdAt = createdAt;
            this.videoCount = videoCount;
            this.currentVideoName = currentVideoName;
        }

        @Override
        public String toString() {
            return name + " [" + hostName + "]";
        }
    }

    public static final class VideoInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        public int id;
        public String roomKey;
        public int uploaderId;
        public String uploaderName;
        public String name;
        public long size;
        public long durationMs;
        public long uploadedAt;
        public String serverPath;

        public VideoInfo(int id, String roomKey, int uploaderId, String uploaderName, String name, long size,
                         long durationMs, long uploadedAt, String serverPath) {
            this.id = id;
            this.roomKey = roomKey;
            this.uploaderId = uploaderId;
            this.uploaderName = uploaderName;
            this.name = name;
            this.size = size;
            this.durationMs = durationMs;
            this.uploadedAt = uploadedAt;
            this.serverPath = serverPath;
        }

        @Override
        public String toString() {
            return name + "  (" + formatDuration(durationMs) + ")";
        }
    }

    public static final class MemberInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        public int userId;
        public String nickname;
        public boolean host;
        public boolean online;

        public MemberInfo(int userId, String nickname, boolean host, boolean online) {
            this.userId = userId;
            this.nickname = nickname;
            this.host = host;
            this.online = online;
        }

        @Override
        public String toString() {
            return nickname + (host ? " (房主)" : "") + (online ? "" : " (离线)");
        }
    }

    public static final class RoomInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        public String key;
        public String name;
        public int hostId;
        public String hostName;
        public int currentVideoId;
        public String currentVideoName;
        public long durationMs;
        public int maxMembers;
        public boolean playing;
        public long positionMs;
        public long createdAt;
        public List<MemberInfo> members = new ArrayList<>();
        public List<VideoInfo> videos = new ArrayList<>();

        public RoomInfo(String key, String name, int hostId, String hostName, int currentVideoId,
                        String currentVideoName, long durationMs, int maxMembers,
                        boolean playing, long positionMs, long createdAt) {
            this.key = key;
            this.name = name;
            this.hostId = hostId;
            this.hostName = hostName;
            this.currentVideoId = currentVideoId;
            this.currentVideoName = currentVideoName;
            this.durationMs = durationMs;
            this.maxMembers = maxMembers;
            this.playing = playing;
            this.positionMs = positionMs;
            this.createdAt = createdAt;
        }
    }

    public static final class ChatMessage implements Serializable {
        private static final long serialVersionUID = 1L;

        public String roomKey;
        public String sender;
        public String content;
        public long timestamp;

        public ChatMessage(String roomKey, String sender, String content, long timestamp) {
            this.roomKey = roomKey;
            this.sender = sender;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    public static String formatBytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        double kb = size / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.1f GB", mb / 1024.0);
    }

    public static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    public static String formatTime(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(timestamp));
    }
}
