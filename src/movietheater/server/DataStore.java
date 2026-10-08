package movietheater.server;

import java.util.List;

interface DataStore {
    UserRecord register(String username, String passwordHash, String nickname) throws StoreException;

    UserRecord authenticate(String username, String passwordHash) throws StoreException;

    List<UserRecord> listUsers() throws StoreException;

    void updateUser(int userId, String nickname, String passwordHash) throws StoreException;

    RoomRecord createRoom(String key, String name, int hostId, String hostName, int maxMembers) throws StoreException;

    RoomRecord findRoom(String roomKey) throws StoreException;

    List<RoomRecord> listRooms() throws StoreException;

    List<RoomRecord> listRoomsForUser(int userId) throws StoreException;

    void rememberRoomMember(String roomKey, int userId) throws StoreException;

    void deleteRoom(String roomKey, int hostId) throws StoreException;

    List<VideoRecord> listRoomVideos(String roomKey) throws StoreException;

    VideoRecord addVideo(String roomKey, int uploaderId, String uploaderName, String name,
                         long size, long durationMs, String serverPath) throws StoreException;

    VideoRecord findVideo(int videoId) throws StoreException;

    void deleteVideo(String roomKey, int videoId, int userId) throws StoreException;

    void selectVideo(String roomKey, int videoId, int userId) throws StoreException;

    void updateVideoDuration(String roomKey, int videoId, long durationMs) throws StoreException;

    void saveRoomPlayback(String roomKey, boolean playing, long positionMs, long lastUpdatedAt) throws StoreException;

    void saveChat(String roomKey, int userId, String nickname, String content, long timestamp) throws StoreException;
}

final class StoreException extends Exception {
    StoreException(String message) {
        super(message);
    }

    StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

final class UserRecord implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    int id;
    String username;
    String passwordHash;
    String nickname;
    long createdAt;

    UserRecord(int id, String username, String passwordHash, String nickname, long createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.createdAt = createdAt;
    }
}

final class RoomRecord implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    String key;
    String name;
    int hostId;
    String hostName;
    int maxMembers;
    int currentVideoId;
    long createdAt;
    boolean playing;
    long positionMs;
    long lastUpdatedAt;

    RoomRecord(String key, String name, int hostId, String hostName, int maxMembers, long createdAt) {
        this.key = key;
        this.name = name;
        this.hostId = hostId;
        this.hostName = hostName;
        this.maxMembers = maxMembers;
        this.createdAt = createdAt;
        this.currentVideoId = 0;
        this.playing = false;
        this.positionMs = 0;
        this.lastUpdatedAt = System.currentTimeMillis();
    }
}

final class VideoRecord implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    int id;
    String roomKey;
    int uploaderId;
    String uploaderName;
    String name;
    long size;
    long durationMs;
    long uploadedAt;
    String serverPath;

    VideoRecord(int id, String roomKey, int uploaderId, String uploaderName, String name, long size,
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
}
