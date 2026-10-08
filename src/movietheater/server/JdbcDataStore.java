package movietheater.server;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class JdbcDataStore implements DataStore {
    private final String url;
    private final String user;
    private final String password;

    JdbcDataStore(Properties props) throws StoreException {
        this.url = props.getProperty("db.url", "").trim();
        this.user = props.getProperty("db.user", "").trim();
        this.password = props.getProperty("db.password", "");
        if (url.isBlank()) {
            throw new StoreException("未配置 db.url");
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new StoreException("未找到 MySQL JDBC 驱动，请确认 lib/mysql-connector-j-9.4.0.jar 存在", e);
        }
        initialize();
    }

    @Override
    public UserRecord register(String username, String passwordHash, String nickname) throws StoreException {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO users(username, password_hash, nickname, created_at) VALUES(?, ?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, nickname);
            ps.setLong(4, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return new UserRecord(rs.getInt(1), username, passwordHash, nickname, now);
                }
            }
            throw new StoreException("读取新用户编号失败");
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                throw new StoreException("用户名已存在", e);
            }
            throw new StoreException("注册用户失败", e);
        }
    }

    @Override
    public UserRecord authenticate(String username, String passwordHash) throws StoreException {
        String sql = "SELECT id, username, password_hash, nickname, created_at FROM users WHERE username = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new StoreException("用户不存在");
                }
                UserRecord user = readUser(rs);
                if (!user.passwordHash.equals(passwordHash)) {
                    throw new StoreException("密码错误");
                }
                return user;
            }
        } catch (SQLException e) {
            throw new StoreException("登录查询失败", e);
        }
    }

    @Override
    public List<UserRecord> listUsers() throws StoreException {
        List<UserRecord> users = new ArrayList<>();
        String sql = "SELECT id, username, password_hash, nickname, created_at FROM users ORDER BY id";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(readUser(rs));
            }
            return users;
        } catch (SQLException e) {
            throw new StoreException("读取用户列表失败", e);
        }
    }

    @Override
    public void updateUser(int userId, String nickname, String passwordHash) throws StoreException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            UserRecord oldUser = findUser(conn, userId);
            String newNickname = nickname == null || nickname.isBlank() ? oldUser.nickname : nickname.trim();
            String newPasswordHash = passwordHash == null || passwordHash.isBlank() ? oldUser.passwordHash : passwordHash;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET nickname = ?, password_hash = ? WHERE id = ?")) {
                ps.setString(1, newNickname);
                ps.setString(2, newPasswordHash);
                ps.setInt(3, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rooms SET host_name = ? WHERE host_id = ?")) {
                ps.setString(1, newNickname);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE videos SET uploader_name = ? WHERE uploader_id = ?")) {
                ps.setString(1, newNickname);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE chats SET nickname = ? WHERE user_id = ?")) {
                ps.setString(1, newNickname);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new StoreException("更新用户信息失败", e);
        }
    }

    @Override
    public RoomRecord createRoom(String key, String name, int hostId, String hostName, int maxMembers) throws StoreException {
        long now = System.currentTimeMillis();
        String normalizedKey = normalizeRoomKey(key);
        String sql = "INSERT INTO rooms(room_key, name, host_id, host_name, max_members, created_at, playing, position_ms, last_updated_at) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, normalizedKey);
                ps.setString(2, name);
                ps.setInt(3, hostId);
                ps.setString(4, hostName);
                ps.setInt(5, Math.max(2, maxMembers));
                ps.setLong(6, now);
                ps.setBoolean(7, false);
                ps.setLong(8, 0);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
            rememberRoomMember(conn, normalizedKey, hostId);
            conn.commit();
            return findRoom(normalizedKey);
        } catch (SQLException e) {
            throw new StoreException("创建房间失败", e);
        }
    }

    @Override
    public RoomRecord findRoom(String roomKey) throws StoreException {
        String sql = "SELECT room_key, name, host_id, host_name, max_members, current_video_id, created_at, "
                + "playing, position_ms, last_updated_at FROM rooms WHERE room_key = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeRoomKey(roomKey));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new StoreException("房间不存在");
                }
                return readRoom(rs);
            }
        } catch (SQLException e) {
            throw new StoreException("读取房间失败", e);
        }
    }

    @Override
    public List<RoomRecord> listRooms() throws StoreException {
        String sql = "SELECT room_key, name, host_id, host_name, max_members, current_video_id, created_at, "
                + "playing, position_ms, last_updated_at FROM rooms ORDER BY created_at DESC";
        List<RoomRecord> rooms = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rooms.add(readRoom(rs));
            }
            return rooms;
        } catch (SQLException e) {
            throw new StoreException("读取房间列表失败", e);
        }
    }

    @Override
    public List<RoomRecord> listRoomsForUser(int userId) throws StoreException {
        String sql = "SELECT DISTINCT r.room_key, r.name, r.host_id, r.host_name, r.max_members, r.current_video_id, "
                + "r.created_at, r.playing, r.position_ms, r.last_updated_at "
                + "FROM rooms r LEFT JOIN room_members m ON r.room_key = m.room_key "
                + "WHERE r.host_id = ? OR m.user_id = ? ORDER BY r.created_at DESC";
        List<RoomRecord> rooms = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rooms.add(readRoom(rs));
                }
            }
            return rooms;
        } catch (SQLException e) {
            throw new StoreException("读取房间列表失败", e);
        }
    }

    @Override
    public void rememberRoomMember(String roomKey, int userId) throws StoreException {
        findRoom(roomKey);
        try (Connection conn = connect()) {
            rememberRoomMember(conn, normalizeRoomKey(roomKey), userId);
        } catch (SQLException e) {
            throw new StoreException("保存房间成员关系失败", e);
        }
    }

    @Override
    public void deleteRoom(String roomKey, int hostId) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        if (room.hostId != hostId) {
            throw new StoreException("只有房主可以删除房间");
        }
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM rooms WHERE room_key = ? AND host_id = ?")) {
            ps.setString(1, room.key);
            ps.setInt(2, hostId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("删除房间失败", e);
        }
    }

    @Override
    public List<VideoRecord> listRoomVideos(String roomKey) throws StoreException {
        findRoom(roomKey);
        String sql = "SELECT id, room_key, uploader_id, uploader_name, name, size, duration_ms, uploaded_at, server_path "
                + "FROM videos WHERE room_key = ? ORDER BY uploaded_at DESC";
        List<VideoRecord> videos = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeRoomKey(roomKey));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    videos.add(readVideo(rs));
                }
            }
            return videos;
        } catch (SQLException e) {
            throw new StoreException("读取房间视频库失败", e);
        }
    }

    @Override
    public VideoRecord addVideo(String roomKey, int uploaderId, String uploaderName, String name,
                                long size, long durationMs, String serverPath) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO videos(room_key, uploader_id, uploader_name, name, size, duration_ms, uploaded_at, server_path) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            int id;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, room.key);
                ps.setInt(2, uploaderId);
                ps.setString(3, uploaderName);
                ps.setString(4, name);
                ps.setLong(5, size);
                ps.setLong(6, durationMs);
                ps.setLong(7, now);
                ps.setString(8, serverPath);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) {
                        throw new StoreException("读取新视频编号失败");
                    }
                    id = rs.getInt(1);
                }
            }
            if (room.currentVideoId <= 0) {
                resetCurrentVideo(conn, room.key, id, now);
            }
            conn.commit();
            return findVideo(id);
        } catch (SQLException e) {
            throw new StoreException("添加房间视频失败", e);
        }
    }

    @Override
    public VideoRecord findVideo(int videoId) throws StoreException {
        String sql = "SELECT id, room_key, uploader_id, uploader_name, name, size, duration_ms, uploaded_at, server_path "
                + "FROM videos WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, videoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new StoreException("视频不存在");
                }
                return readVideo(rs);
            }
        } catch (SQLException e) {
            throw new StoreException("读取视频失败", e);
        }
    }

    @Override
    public void deleteVideo(String roomKey, int videoId, int userId) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        VideoRecord video = findVideo(videoId);
        if (!room.key.equals(video.roomKey)) {
            throw new StoreException("视频不属于当前房间");
        }
        if (room.hostId != userId && video.uploaderId != userId) {
            throw new StoreException("只有房主或上传者可以删除该视频");
        }
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM videos WHERE id = ? AND room_key = ?")) {
                ps.setInt(1, videoId);
                ps.setString(2, room.key);
                ps.executeUpdate();
            }
            if (room.currentVideoId == videoId) {
                resetCurrentVideo(conn, room.key, firstVideoId(conn, room.key), System.currentTimeMillis());
            }
            conn.commit();
        } catch (SQLException e) {
            throw new StoreException("删除视频失败", e);
        }
    }

    @Override
    public void selectVideo(String roomKey, int videoId, int userId) throws StoreException {
        RoomRecord room = findRoom(roomKey);
        if (room.hostId != userId) {
            throw new StoreException("只有房主可以切换当前播放视频");
        }
        VideoRecord video = findVideo(videoId);
        if (!room.key.equals(video.roomKey)) {
            throw new StoreException("视频不属于当前房间");
        }
        try (Connection conn = connect()) {
            resetCurrentVideo(conn, room.key, videoId, System.currentTimeMillis());
        } catch (SQLException e) {
            throw new StoreException("切换当前播放视频失败", e);
        }
    }

    @Override
    public void updateVideoDuration(String roomKey, int videoId, long durationMs) throws StoreException {
        if (durationMs <= 0) {
            return;
        }
        RoomRecord room = findRoom(roomKey);
        VideoRecord video = findVideo(videoId);
        if (!room.key.equals(video.roomKey)) {
            throw new StoreException("视频不属于当前房间");
        }
        String sql = "UPDATE videos SET duration_ms = ? WHERE id = ? AND room_key = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, durationMs);
            ps.setInt(2, videoId);
            ps.setString(3, room.key);
            ps.executeUpdate();
            if (room.currentVideoId == videoId && room.positionMs > durationMs) {
                saveRoomPlayback(room.key, room.playing, durationMs, System.currentTimeMillis());
            }
        } catch (SQLException e) {
            throw new StoreException("更新视频时长失败", e);
        }
    }

    @Override
    public void saveRoomPlayback(String roomKey, boolean playing, long positionMs, long lastUpdatedAt) throws StoreException {
        String sql = "UPDATE rooms SET playing = ?, position_ms = ?, last_updated_at = ? WHERE room_key = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, playing);
            ps.setLong(2, Math.max(0, positionMs));
            ps.setLong(3, lastUpdatedAt <= 0 ? System.currentTimeMillis() : lastUpdatedAt);
            ps.setString(4, normalizeRoomKey(roomKey));
            if (ps.executeUpdate() == 0) {
                throw new StoreException("房间不存在");
            }
        } catch (SQLException e) {
            throw new StoreException("保存房间播放状态失败", e);
        }
    }

    @Override
    public void saveChat(String roomKey, int userId, String nickname, String content, long timestamp) throws StoreException {
        findRoom(roomKey);
        String sql = "INSERT INTO chats(room_key, user_id, nickname, content, created_at) VALUES(?, ?, ?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeRoomKey(roomKey));
            ps.setInt(2, userId);
            ps.setString(3, nickname);
            ps.setString(4, content);
            ps.setLong(5, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("保存聊天记录失败", e);
        }
    }

    private void initialize() throws StoreException {
        try {
            createDatabaseIfNeeded();
            try (Connection conn = connect();
                 Statement st = conn.createStatement()) {
                dropObsoleteTablesIfNeeded(conn);
                st.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                        + "id INT PRIMARY KEY AUTO_INCREMENT, "
                        + "username VARCHAR(50) NOT NULL UNIQUE, "
                        + "password_hash VARCHAR(128) NOT NULL, "
                        + "nickname VARCHAR(50) NOT NULL, "
                        + "created_at BIGINT NOT NULL"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS rooms ("
                        + "room_key VARCHAR(16) PRIMARY KEY, "
                        + "name VARCHAR(100) NOT NULL, "
                        + "host_id INT NOT NULL, "
                        + "host_name VARCHAR(50) NOT NULL, "
                        + "max_members INT NOT NULL, "
                        + "current_video_id INT NOT NULL DEFAULT 0, "
                        + "created_at BIGINT NOT NULL, "
                        + "playing BOOLEAN NOT NULL DEFAULT FALSE, "
                        + "position_ms BIGINT NOT NULL DEFAULT 0, "
                        + "last_updated_at BIGINT NOT NULL, "
                        + "INDEX idx_rooms_host(host_id), "
                        + "CONSTRAINT fk_rooms_host FOREIGN KEY(host_id) REFERENCES users(id) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS room_members ("
                        + "room_key VARCHAR(16) NOT NULL, "
                        + "user_id INT NOT NULL, "
                        + "joined_at BIGINT NOT NULL, "
                        + "PRIMARY KEY(room_key, user_id), "
                        + "CONSTRAINT fk_room_members_room FOREIGN KEY(room_key) REFERENCES rooms(room_key) ON DELETE CASCADE, "
                        + "CONSTRAINT fk_room_members_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS videos ("
                        + "id INT PRIMARY KEY AUTO_INCREMENT, "
                        + "room_key VARCHAR(16) NOT NULL, "
                        + "uploader_id INT NOT NULL, "
                        + "uploader_name VARCHAR(50) NOT NULL, "
                        + "name VARCHAR(255) NOT NULL, "
                        + "size BIGINT NOT NULL, "
                        + "duration_ms BIGINT NOT NULL, "
                        + "uploaded_at BIGINT NOT NULL, "
                        + "server_path VARCHAR(500) NOT NULL, "
                        + "INDEX idx_videos_room(room_key), "
                        + "CONSTRAINT fk_videos_room FOREIGN KEY(room_key) REFERENCES rooms(room_key) ON DELETE CASCADE, "
                        + "CONSTRAINT fk_videos_uploader FOREIGN KEY(uploader_id) REFERENCES users(id) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS chats ("
                        + "id INT PRIMARY KEY AUTO_INCREMENT, "
                        + "room_key VARCHAR(16) NOT NULL, "
                        + "user_id INT NOT NULL, "
                        + "nickname VARCHAR(50) NOT NULL, "
                        + "content VARCHAR(1000) NOT NULL, "
                        + "created_at BIGINT NOT NULL, "
                        + "INDEX idx_chats_room(room_key), "
                        + "CONSTRAINT fk_chats_room FOREIGN KEY(room_key) REFERENCES rooms(room_key) ON DELETE CASCADE, "
                        + "CONSTRAINT fk_chats_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        } catch (SQLException e) {
            throw new StoreException("初始化 MySQL 数据表失败", e);
        }
    }

    private static void dropObsoleteTablesIfNeeded(Connection conn) throws SQLException {
        boolean legacyVideos = tableExists(conn, "videos") && !columnExists(conn, "videos", "room_key");
        boolean legacyRooms = tableExists(conn, "rooms") && !columnExists(conn, "rooms", "room_key");
        boolean legacyMembers = tableExists(conn, "room_members") && !columnExists(conn, "room_members", "room_key");
        if (!legacyVideos && !legacyRooms && !legacyMembers) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS chats");
            st.executeUpdate("DROP TABLE IF EXISTS videos");
            st.executeUpdate("DROP TABLE IF EXISTS room_members");
            if (legacyRooms) {
                st.executeUpdate("DROP TABLE IF EXISTS rooms");
            }
        }
        System.out.println("检测到旧版个人视频库数据库结构，已重建为房间视频库结构。");
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, table, new String[]{"TABLE"})) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, table.toUpperCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }

    private void createDatabaseIfNeeded() throws SQLException {
        String database = databaseName(url);
        if (database.isBlank()) {
            return;
        }
        String serverUrl = urlWithoutDatabase(url);
        try (Connection conn = DriverManager.getConnection(serverUrl, user, password);
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static UserRecord findUser(Connection conn, int userId) throws SQLException, StoreException {
        String sql = "SELECT id, username, password_hash, nickname, created_at FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new StoreException("用户不存在");
                }
                return readUser(rs);
            }
        }
    }

    private static UserRecord readUser(ResultSet rs) throws SQLException {
        return new UserRecord(rs.getInt("id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("nickname"), rs.getLong("created_at"));
    }

    private static RoomRecord readRoom(ResultSet rs) throws SQLException {
        RoomRecord room = new RoomRecord(rs.getString("room_key"), rs.getString("name"),
                rs.getInt("host_id"), rs.getString("host_name"), rs.getInt("max_members"), rs.getLong("created_at"));
        room.currentVideoId = rs.getInt("current_video_id");
        room.playing = rs.getBoolean("playing");
        room.positionMs = rs.getLong("position_ms");
        room.lastUpdatedAt = rs.getLong("last_updated_at");
        return room;
    }

    private static VideoRecord readVideo(ResultSet rs) throws SQLException {
        return new VideoRecord(rs.getInt("id"), rs.getString("room_key"), rs.getInt("uploader_id"),
                rs.getString("uploader_name"), rs.getString("name"), rs.getLong("size"),
                rs.getLong("duration_ms"), rs.getLong("uploaded_at"), rs.getString("server_path"));
    }

    private static void rememberRoomMember(Connection conn, String roomKey, int userId) throws SQLException {
        String sql = "INSERT IGNORE INTO room_members(room_key, user_id, joined_at) VALUES(?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomKey);
            ps.setInt(2, userId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void resetCurrentVideo(Connection conn, String roomKey, int videoId, long now) throws SQLException {
        String sql = "UPDATE rooms SET current_video_id = ?, playing = FALSE, position_ms = 0, last_updated_at = ? WHERE room_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, videoId);
            ps.setLong(2, now);
            ps.setString(3, roomKey);
            ps.executeUpdate();
        }
    }

    private static int firstVideoId(Connection conn, String roomKey) throws SQLException {
        String sql = "SELECT id FROM videos WHERE room_key = ? ORDER BY uploaded_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : 0;
            }
        }
    }

    private static boolean isDuplicate(SQLException e) {
        return "23000".equals(e.getSQLState()) || e.getErrorCode() == 1062;
    }

    private static String normalizeRoomKey(String roomKey) {
        return roomKey == null ? "" : roomKey.trim().toUpperCase();
    }

    private static String databaseName(String jdbcUrl) {
        String base = jdbcUrl;
        int question = base.indexOf('?');
        if (question >= 0) {
            base = base.substring(0, question);
        }
        int slash = base.lastIndexOf('/');
        if (slash < 0 || slash == base.length() - 1) {
            return "";
        }
        return base.substring(slash + 1).replace("`", "");
    }

    private static String urlWithoutDatabase(String jdbcUrl) {
        int question = jdbcUrl.indexOf('?');
        String query = question >= 0 ? jdbcUrl.substring(question) : "";
        String base = question >= 0 ? jdbcUrl.substring(0, question) : jdbcUrl;
        int slash = base.lastIndexOf('/');
        if (slash < "jdbc:mysql://".length()) {
            return jdbcUrl;
        }
        return base.substring(0, slash + 1) + query;
    }
}
