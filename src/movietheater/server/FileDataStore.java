package movietheater.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FileDataStore implements DataStore {
    private final Path storeFile;
    private State state;

    FileDataStore(Path dataDir) throws StoreException {
        try {
            Files.createDirectories(dataDir);
            this.storeFile = dataDir.resolve("store.dat");
            this.state = load();
        } catch (IOException e) {
            throw new StoreException("初始化本地数据文件失败", e);
        }
    }

    @Override
    public synchronized UserRecord register(String username, String passwordHash, String nickname) throws StoreException {
        if (state.usersByName.containsKey(username)) {
            throw new StoreException("用户名已存在");
        }
        int id = state.nextUserId++;
        UserRecord user = new UserRecord(id, username, passwordHash, nickname, System.currentTimeMillis());
        state.usersById.put(id, user);
        state.usersByName.put(username, id);
        persist();
        return user;
    }

    @Override
    public synchronized UserRecord authenticate(String username, String passwordHash) throws StoreException {
        Integer id = state.usersByName.get(username);
        if (id == null) {
            throw new StoreException("用户不存在");
        }
        UserRecord user = state.usersById.get(id);
        if (!user.passwordHash.equals(passwordHash)) {
            throw new StoreException("密码错误");
        }
        return user;
    }

    @Override
    public synchronized List<UserRecord> listUsers() {
        List<UserRecord> result = new ArrayList<>(state.usersById.values());
        result.sort((a, b) -> Integer.compare(a.id, b.id));
        return result;
    }

    @Override
    public synchronized void updateUser(int userId, String nickname, String passwordHash) throws StoreException {
        UserRecord user = state.usersById.get(userId);
        if (user == null) {
            throw new StoreException("用户不存在");
        }
        if (nickname != null && !nickname.isBlank()) {
            user.nickname = nickname.trim();
            for (RoomRecord room : state.roomsByKey.values()) {
                if (room.hostId == userId) {
                    room.hostName = user.nickname;
                }
            }
            for (VideoRecord video : state.videosById.values()) {
                if (video.uploaderId == userId) {
                    video.uploaderName = user.nickname;
                }
            }
        }
        if (passwordHash != null && !passwordHash.isBlank()) {
            user.passwordHash = passwordHash;
        }
        persist();
    }

    @Override
    public synchronized RoomRecord createRoom(String key, String name, int hostId, String hostName, int maxMembers) throws StoreException {
        if (state.roomsByKey.containsKey(key)) {
            throw new StoreException("房间 key 已存在");
        }
        RoomRecord room = new RoomRecord(key, name, hostId, hostName, Math.max(2, maxMembers), System.currentTimeMillis());
        state.roomsByKey.put(key, room);
        rememberRoomMemberInternal(key, hostId);
        persist();
        return room;
    }

    @Override
    public synchronized RoomRecord findRoom(String roomKey) throws StoreException {
        RoomRecord room = state.roomsByKey.get(normalizeRoomKey(roomKey));
        if (room == null) {
            throw new StoreException("房间不存在");
        }
        return room;
    }

    @Override
    public synchronized List<RoomRecord> listRooms() {
        List<RoomRecord> result = new ArrayList<>(state.roomsByKey.values());
        result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return result;
    }

    @Override
    public synchronized List<RoomRecord> listRoomsForUser(int userId) {
        List<RoomRecord> result = new ArrayList<>();
        for (RoomRecord room : state.roomsByKey.values()) {
            Set<Integer> members = state.roomMembers.get(room.key);
            if (room.hostId == userId || (members != null && members.contains(userId))) {
                result.add(room);
            }
        }
        result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return result;
    }

    @Override
    public synchronized void rememberRoomMember(String roomKey, int userId) throws StoreException {
        findRoom(roomKey);
        rememberRoomMemberInternal(normalizeRoomKey(roomKey), userId);
        persist();
    }

    @Override
    public synchronized void deleteRoom(String roomKey, int hostId) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        if (room.hostId != hostId) {
            throw new StoreException("只有房主可以删除房间");
        }
        List<Integer> removeVideoIds = new ArrayList<>();
        for (VideoRecord video : state.videosById.values()) {
            if (room.key.equals(video.roomKey)) {
                removeVideoIds.add(video.id);
            }
        }
        for (Integer id : removeVideoIds) {
            state.videosById.remove(id);
        }
        state.roomMembers.remove(room.key);
        state.roomsByKey.remove(room.key);
        persist();
    }

    @Override
    public synchronized List<VideoRecord> listRoomVideos(String roomKey) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        List<VideoRecord> result = new ArrayList<>();
        for (VideoRecord video : state.videosById.values()) {
            if (room.key.equals(video.roomKey)) {
                result.add(video);
            }
        }
        result.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
        return result;
    }

    @Override
    public synchronized VideoRecord addVideo(String roomKey, int uploaderId, String uploaderName, String name,
                                             long size, long durationMs, String serverPath) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        int id = state.nextVideoId++;
        VideoRecord video = new VideoRecord(id, room.key, uploaderId, uploaderName, name, size, durationMs,
                System.currentTimeMillis(), serverPath);
        state.videosById.put(id, video);
        if (room.currentVideoId <= 0) {
            room.currentVideoId = id;
            room.playing = false;
            room.positionMs = 0;
            room.lastUpdatedAt = System.currentTimeMillis();
        }
        persist();
        return video;
    }

    @Override
    public synchronized VideoRecord findVideo(int videoId) throws StoreException {
        VideoRecord video = state.videosById.get(videoId);
        if (video == null) {
            throw new StoreException("视频不存在");
        }
        return video;
    }

    @Override
    public synchronized void deleteVideo(String roomKey, int videoId, int userId) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        VideoRecord video = findVideo(videoId);
        if (!room.key.equals(video.roomKey)) {
            throw new StoreException("视频不属于当前房间");
        }
        if (room.hostId != userId && video.uploaderId != userId) {
            throw new StoreException("只有房主或上传者可以删除该视频");
        }
        state.videosById.remove(videoId);
        if (room.currentVideoId == videoId) {
            room.currentVideoId = firstVideoId(room.key);
            room.playing = false;
            room.positionMs = 0;
            room.lastUpdatedAt = System.currentTimeMillis();
        }
        persist();
    }

    @Override
    public synchronized void selectVideo(String roomKey, int videoId, int userId) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        if (room.hostId != userId) {
            throw new StoreException("只有房主可以切换当前播放视频");
        }
        VideoRecord video = findVideo(videoId);
        if (!room.key.equals(video.roomKey)) {
            throw new StoreException("视频不属于当前房间");
        }
        room.currentVideoId = videoId;
        room.playing = false;
        room.positionMs = 0;
        room.lastUpdatedAt = System.currentTimeMillis();
        persist();
    }

    @Override
    public synchronized void updateVideoDuration(String roomKey, int videoId, long durationMs) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        VideoRecord video = findVideo(videoId);
        if (!room.key.equals(video.roomKey)) {
            throw new StoreException("视频不属于当前房间");
        }
        if (durationMs <= 0) {
            return;
        }
        video.durationMs = durationMs;
        if (room.currentVideoId == videoId) {
            room.positionMs = Math.max(0, Math.min(room.positionMs, durationMs));
        }
        persist();
    }

    @Override
    public synchronized void saveRoomPlayback(String roomKey, boolean playing, long positionMs, long lastUpdatedAt) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        room.playing = playing;
        room.positionMs = Math.max(0, positionMs);
        room.lastUpdatedAt = lastUpdatedAt <= 0 ? System.currentTimeMillis() : lastUpdatedAt;
        persist();
    }

    @Override
    public synchronized void saveChat(String roomKey, int userId, String nickname, String content, long timestamp) throws StoreException {
        findRoom(roomKey);
        state.chats.add(new ChatRecord(normalizeRoomKey(roomKey), userId, nickname, content, timestamp));
        persist();
    }

    private int firstVideoId(String roomKey) {
        int result = 0;
        long newest = Long.MIN_VALUE;
        for (VideoRecord video : state.videosById.values()) {
            if (roomKey.equals(video.roomKey) && video.uploadedAt > newest) {
                newest = video.uploadedAt;
                result = video.id;
            }
        }
        return result;
    }

    private void rememberRoomMemberInternal(String roomKey, int userId) {
        state.roomMembers.computeIfAbsent(roomKey, key -> new HashSet<>()).add(userId);
    }

    private State load() throws StoreException {
        if (!Files.exists(storeFile)) {
            return new State();
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(storeFile))) {
            Object loaded = in.readObject();
            if (loaded instanceof State) {
                State loadedState = (State) loaded;
                loadedState.ensureCollections();
                return loadedState;
            }
            return new State();
        } catch (IOException | ClassNotFoundException e) {
            throw new StoreException("读取本地数据文件失败。若这是旧版本数据，请删除 server-data/store.dat 后重新启动。", e);
        }
    }

    private void persist() throws StoreException {
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(storeFile))) {
            out.writeObject(state);
        } catch (IOException e) {
            throw new StoreException("保存本地数据文件失败", e);
        }
    }

    private static String normalizeRoomKey(String roomKey) {
        return roomKey == null ? "" : roomKey.trim().toUpperCase();
    }

    private static final class State implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        int nextUserId = 1;
        int nextVideoId = 1;
        Map<Integer, UserRecord> usersById = new HashMap<>();
        Map<String, Integer> usersByName = new HashMap<>();
        Map<String, RoomRecord> roomsByKey = new HashMap<>();
        Map<Integer, VideoRecord> videosById = new HashMap<>();
        Map<String, Set<Integer>> roomMembers = new HashMap<>();
        List<ChatRecord> chats = new ArrayList<>();

        void ensureCollections() {
            if (usersById == null) {
                usersById = new HashMap<>();
            }
            if (usersByName == null) {
                usersByName = new HashMap<>();
            }
            if (roomsByKey == null) {
                roomsByKey = new HashMap<>();
            }
            if (videosById == null) {
                videosById = new HashMap<>();
            }
            if (roomMembers == null) {
                roomMembers = new HashMap<>();
            }
            if (chats == null) {
                chats = new ArrayList<>();
            }
        }
    }

    private static final class ChatRecord implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        String roomKey;
        int userId;
        String nickname;
        String content;
        long timestamp;

        ChatRecord(String roomKey, int userId, String nickname, String content, long timestamp) {
            this.roomKey = roomKey;
            this.userId = userId;
            this.nickname = nickname;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
