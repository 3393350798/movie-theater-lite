package movietheater.server;

import movietheater.common.Protocol;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

public final class MovieTheaterServer {
    private static final long DEFAULT_VIDEO_DURATION_MS = 10 * 60 * 1000L;
    private static final BufferedReader CONSOLE_READER =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private final DataStore store;
    private final Path videoDir;
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    private MovieTheaterServer(DataStore store, Path videoDir) throws IOException {
        this.store = store;
        this.videoDir = videoDir;
        Files.createDirectories(videoDir);
    }

    public static void main(String[] args) throws Exception {
        Properties props = loadProperties();
        int port = Integer.parseInt(props.getProperty("server.port", "5050"));
        Path dataDir = Paths.get(props.getProperty("data.dir", "server-data"));
        Path videoDir = dataDir.resolve("rooms");
        StoreSelection selection = selectStore(props, dataDir);
        MovieTheaterServer server = new MovieTheaterServer(selection.store, videoDir);
        System.out.println(selection.message);
        SwingUtilities.invokeLater(() -> new ServerAdminFrame(selection.store, selection.message).setVisible(true));
        while (true) {
            try {
                server.start(port);
                return;
            } catch (BindException e) {
                int newPort = handlePortConflict(port);
                if (newPort <= 0) {
                    System.exit(2);
                }
                port = newPort;
            }
        }
    }

    private static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        Path file = Paths.get("server.properties");
        if (Files.exists(file)) {
            try (java.io.InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        copySystemProperty(props, "server.port");
        copySystemProperty(props, "data.dir");
        copySystemProperty(props, "storage");
        copySystemProperty(props, "db.url");
        copySystemProperty(props, "db.user");
        copySystemProperty(props, "db.password");
        return props;
    }

    private static void copySystemProperty(Properties props, String key) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private static StoreSelection selectStore(Properties props, Path dataDir) throws IOException, StoreException {
        String mode = props.getProperty("storage", "auto").trim().toLowerCase();
        if (isInteractive()) {
            System.out.println();
            System.out.println("请选择服务端数据保存方式：");
            System.out.println("1. 严格 MySQL：必须连接数据库，失败则提示重新配置");
            System.out.println("2. 兼容模式：优先使用 MySQL，失败时自动回退本地文件");
            System.out.println("3. 本地文件：使用 server-data/store.dat 保存数据");
            String input = prompt("请输入 1/2/3，直接回车使用配置值 [" + mode + "]：").trim();
            if (!input.isBlank()) {
                mode = parseStorageMode(input);
            }
        }
        switch (parseStorageMode(mode)) {
            case "mysql":
                return strictMysqlStore(props);
            case "file":
                return fileStore(dataDir);
            case "auto":
            default:
                try {
                    DataStore store = new JdbcDataStore(props);
                    return new StoreSelection(store, "使用 MySQL 数据库存储：" + props.getProperty("db.url", ""));
                } catch (StoreException e) {
                    System.err.println("MySQL 连接失败，已切换为本地文件兼容模式：" + rootMessage(e));
                    return fileStore(dataDir);
                }
        }
    }

    private static StoreSelection strictMysqlStore(Properties props) throws IOException, StoreException {
        while (true) {
            try {
                DataStore store = new JdbcDataStore(props);
                return new StoreSelection(store, "使用 MySQL 数据库存储：" + props.getProperty("db.url", ""));
            } catch (StoreException e) {
                System.err.println("MySQL 数据库连接失败：" + rootMessage(e));
                if (!isInteractive()) {
                    throw e;
                }
                String edit = prompt("是否现在填写数据库配置后重试？(y/N)：").trim();
                if (!edit.equalsIgnoreCase("y")) {
                    throw e;
                }
                updateDatabasePropertiesFromConsole(props);
            }
        }
    }

    private static void updateDatabasePropertiesFromConsole(Properties props) throws IOException {
        String url = promptWithDefault("db.url", props.getProperty("db.url", ""));
        String user = promptWithDefault("db.user", props.getProperty("db.user", "root"));
        String password = promptWithDefault("db.password", props.getProperty("db.password", ""));
        props.setProperty("db.url", url);
        props.setProperty("db.user", user);
        props.setProperty("db.password", password);
    }

    private static String promptWithDefault(String key, String current) throws IOException {
        String value = prompt(key + " [" + current + "]：").trim();
        return value.isBlank() ? current : value;
    }

    private static StoreSelection fileStore(Path dataDir) throws StoreException {
        return new StoreSelection(new FileDataStore(dataDir),
                "使用本地文件存储：" + dataDir.resolve("store.dat").toAbsolutePath());
    }

    private static String parseStorageMode(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase();
        if ("1".equals(mode) || "mysql".equals(mode) || "sql".equals(mode) || "strict".equals(mode)) {
            return "mysql";
        }
        if ("3".equals(mode) || "file".equals(mode) || "local".equals(mode)) {
            return "file";
        }
        return "auto";
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }

    private void start(int port) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("多人影院服务端已启动，端口：" + port);
            while (true) {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket);
                clients.add(handler);
                new Thread(handler, "client-" + socket.getPort()).start();
            }
        }
    }

    private static int handlePortConflict(int port) throws IOException {
        System.err.println();
        System.err.println("启动失败：端口 " + port + " 已被占用。");
        if (!isInteractive()) {
            System.err.println("请关闭旧服务端，或修改 server.properties 中的 server.port。");
            return -1;
        }
        List<PortProcess> processes = findPortProcesses(port);
        if (!processes.isEmpty()) {
            System.err.println("检测到以下进程正在监听该端口：");
            for (PortProcess process : processes) {
                System.err.println("PID " + process.pid + "  " + process.commandLine);
            }
            String kill = prompt("是否结束这些进程并重新尝试启动？(y/N)：").trim();
            if (kill.equalsIgnoreCase("y")) {
                terminateProcesses(processes);
                return port;
            }
        } else {
            System.err.println("未能自动识别占用进程。");
        }
        String newPort = prompt("请输入新的服务端端口，直接回车退出：").trim();
        if (newPort.isEmpty()) {
            return -1;
        }
        return Integer.parseInt(newPort);
    }

    private static List<PortProcess> findPortProcesses(int port) {
        Set<Long> pids = new LinkedHashSet<>();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                Process process = new ProcessBuilder("netstat", "-ano", "-p", "TCP")
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("TCP") || !trimmed.contains("LISTENING")) {
                            continue;
                        }
                        String[] parts = trimmed.split("\\s+");
                        if (parts.length >= 5 && isLocalPort(parts[1], port)) {
                            pids.add(Long.parseLong(parts[4]));
                        }
                    }
                }
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The user can still choose a different port if netstat is unavailable.
            }
        }
        List<PortProcess> result = new ArrayList<>();
        long currentPid = ProcessHandle.current().pid();
        for (Long pid : pids) {
            if (pid == currentPid) {
                continue;
            }
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            String commandLine = handle.flatMap(h -> h.info().commandLine())
                    .orElseGet(() -> handle.flatMap(h -> h.info().command()).orElse("未知进程"));
            result.add(new PortProcess(pid, commandLine));
        }
        return result;
    }

    private static boolean isLocalPort(String address, int port) {
        return address.endsWith(":" + port) || address.endsWith("]:" + port);
    }

    private static void terminateProcesses(List<PortProcess> processes) {
        for (PortProcess process : processes) {
            try {
                Optional<ProcessHandle> handle = ProcessHandle.of(process.pid);
                if (handle.isEmpty()) {
                    continue;
                }
                ProcessHandle h = handle.get();
                h.destroy();
                try {
                    h.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception timeout) {
                    if (h.isAlive()) {
                        h.destroyForcibly();
                    }
                }
                System.out.println("已请求结束进程 PID " + process.pid);
            } catch (Exception e) {
                System.err.println("结束进程 PID " + process.pid + " 失败：" + e.getMessage());
            }
        }
    }

    private static boolean isInteractive() {
        return System.console() != null;
    }

    private static String prompt(String message) throws IOException {
        System.out.print(message);
        String line = CONSOLE_READER.readLine();
        return line == null ? "" : line;
    }

    private Protocol.Response handle(ClientHandler handler, Protocol.Request request) {
        try {
            switch (request.type) {
                case Protocol.REGISTER:
                    return register(request);
                case Protocol.LOGIN:
                    return login(handler, request);
                case Protocol.LIST_ROOMS:
                    requireLogin(handler);
                    return listRooms(handler, request);
                case Protocol.CREATE_ROOM:
                    requireLogin(handler);
                    return createRoom(handler, request);
                case Protocol.JOIN_ROOM:
                    requireLogin(handler);
                    return joinRoom(handler, request);
                case Protocol.DELETE_ROOM:
                    requireLogin(handler);
                    return deleteRoom(handler, request);
                case Protocol.LIST_ROOM_VIDEOS:
                    requireLogin(handler);
                    return listRoomVideos(handler, request);
                case Protocol.UPLOAD_VIDEO:
                    requireLogin(handler);
                    return uploadVideo(handler, request);
                case Protocol.DELETE_VIDEO:
                    requireLogin(handler);
                    return deleteVideo(handler, request);
                case Protocol.DOWNLOAD_VIDEO:
                    requireLogin(handler);
                    return downloadVideo(handler, request);
                case Protocol.SELECT_VIDEO:
                    requireLogin(handler);
                    return selectVideo(handler, request);
                case Protocol.UPDATE_VIDEO_DURATION:
                    requireLogin(handler);
                    return updateVideoDuration(handler, request);
                case Protocol.LEAVE_ROOM:
                    requireLogin(handler);
                    return leaveRoom(handler, request);
                case Protocol.CONTROL:
                    requireLogin(handler);
                    return control(handler, request);
                case Protocol.CHAT:
                    requireLogin(handler);
                    return chat(handler, request);
                default:
                    return error(request, "未知请求：" + request.type);
            }
        } catch (StoreException | IOException | IllegalArgumentException e) {
            return error(request, e.getMessage());
        }
    }

    private Protocol.Response register(Protocol.Request request) throws StoreException {
        String username = stringValue(request, "username");
        String password = stringValue(request, "password");
        String nickname = stringValue(request, "nickname");
        UserRecord user = store.register(username, PasswordUtil.hash(password), nickname.isBlank() ? username : nickname);
        return ok(request, "注册成功").put("user", toUser(user));
    }

    private Protocol.Response login(ClientHandler handler, Protocol.Request request) throws StoreException {
        String username = stringValue(request, "username");
        String password = stringValue(request, "password");
        UserRecord user = store.authenticate(username, PasswordUtil.hash(password));
        handler.user = user;
        return ok(request, "登录成功").put("user", toUser(user));
    }

    private Protocol.Response listRooms(ClientHandler handler, Protocol.Request request) throws StoreException {
        List<Protocol.RoomSummary> rooms = new ArrayList<>();
        for (RoomRecord room : store.listRoomsForUser(handler.user.id)) {
            rooms.add(toRoomSummary(room));
        }
        return ok(request, "读取房间列表成功").put("rooms", rooms);
    }

    private Protocol.Response createRoom(ClientHandler handler, Protocol.Request request) throws StoreException {
        String roomName = stringValue(request, "roomName");
        int maxMembers = Math.max(2, intValue(request, "maxMembers"));
        String key = generateKey();
        RoomRecord room = store.createRoom(key,
                roomName.isBlank() ? handler.user.nickname + "的观影房间" : roomName,
                handler.user.id, handler.user.nickname, maxMembers);
        handler.currentRoomKey = room.key;
        Protocol.RoomInfo info = toRoomInfo(room);
        broadcastRoom(room.key);
        return ok(request, "房间创建成功").put("room", info);
    }

    private Protocol.Response joinRoom(ClientHandler handler, Protocol.Request request) throws StoreException {
        String key = stringValue(request, "key").toUpperCase();
        RoomRecord room = store.findRoom(key);
        if (activeMemberCount(key) >= room.maxMembers && !isInRoom(handler.user.id, key)) {
            throw new IllegalArgumentException("房间人数已满");
        }
        store.rememberRoomMember(key, handler.user.id);
        handler.currentRoomKey = key;
        broadcastSystemChat(key, handler.user.nickname + " 加入房间");
        broadcastRoom(key);
        return ok(request, "加入房间成功").put("room", toRoomInfo(room));
    }

    private Protocol.Response deleteRoom(ClientHandler handler, Protocol.Request request) throws StoreException, IOException {
        String key = stringValue(request, "roomKey").toUpperCase();
        RoomRecord room = store.findRoom(key);
        if (room.hostId != handler.user.id) {
            throw new IllegalArgumentException("只有房主可以删除房间");
        }
        List<VideoRecord> videos = store.listRoomVideos(key);
        store.deleteRoom(key, handler.user.id);
        for (VideoRecord video : videos) {
            deleteVideoFile(video);
        }
        for (ClientHandler client : clients) {
            if (key.equals(client.currentRoomKey)) {
                client.currentRoomKey = null;
                client.send(new Protocol.Event(Protocol.EVENT_ROOM_UPDATED).put("room", null));
            }
        }
        return ok(request, "房间已删除");
    }

    private Protocol.Response listRoomVideos(ClientHandler handler, Protocol.Request request) throws StoreException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        requireRoomAccess(handler, roomKey);
        List<Protocol.VideoInfo> videos = new ArrayList<>();
        for (VideoRecord record : store.listRoomVideos(roomKey)) {
            videos.add(toVideoInfo(record));
        }
        return ok(request, "读取房间视频库成功").put("videos", videos);
    }

    private Protocol.Response uploadVideo(ClientHandler handler, Protocol.Request request) throws StoreException, IOException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        requireRoomAccess(handler, roomKey);
        String fileName = safeFileName(stringValue(request, "fileName"));
        byte[] bytes = (byte[]) request.payload.get("bytes");
        long durationMs = longValue(request, "durationMs");
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        Path roomDir = videoDir.resolve(roomKey);
        Files.createDirectories(roomDir);
        String storedName = System.currentTimeMillis() + "_" + handler.user.id + "_" + fileName;
        Path target = roomDir.resolve(storedName);
        Files.write(target, bytes);
        VideoRecord finalRecord = store.addVideo(roomKey, handler.user.id, handler.user.nickname,
                fileName, bytes.length, durationMs, target.toString());
        broadcastRoom(roomKey);
        return ok(request, "上传成功").put("video", toVideoInfo(finalRecord)).put("room", toRoomInfo(store.findRoom(roomKey)));
    }

    private Protocol.Response deleteVideo(ClientHandler handler, Protocol.Request request) throws StoreException, IOException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        int videoId = intValue(request, "videoId");
        requireRoomAccess(handler, roomKey);
        VideoRecord video = store.findVideo(videoId);
        store.deleteVideo(roomKey, videoId, handler.user.id);
        deleteVideoFile(video);
        broadcastRoom(roomKey);
        return ok(request, "删除成功").put("room", toRoomInfo(store.findRoom(roomKey)));
    }

    private Protocol.Response downloadVideo(ClientHandler handler, Protocol.Request request) throws StoreException, IOException {
        int videoId = intValue(request, "videoId");
        VideoRecord video = store.findVideo(videoId);
        requireRoomAccess(handler, video.roomKey);
        Path path = Paths.get(video.serverPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("服务器视频文件不存在");
        }
        return ok(request, "下载成功")
                .put("fileName", video.name)
                .put("bytes", Files.readAllBytes(path));
    }

    private Protocol.Response selectVideo(ClientHandler handler, Protocol.Request request) throws StoreException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        int videoId = intValue(request, "videoId");
        requireRoomAccess(handler, roomKey);
        store.selectVideo(roomKey, videoId, handler.user.id);
        broadcastRoom(roomKey);
        return ok(request, "当前播放视频已切换").put("room", toRoomInfo(store.findRoom(roomKey)));
    }

    private Protocol.Response updateVideoDuration(ClientHandler handler, Protocol.Request request) throws StoreException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        int videoId = intValue(request, "videoId");
        long durationMs = longValue(request, "durationMs");
        requireRoomAccess(handler, roomKey);
        VideoRecord old = store.findVideo(videoId);
        long oldDuration = old.durationMs;
        if (durationMs > 0 && Math.abs(durationMs - oldDuration) > 1000) {
            store.updateVideoDuration(roomKey, videoId, durationMs);
            broadcastRoom(roomKey);
        }
        return ok(request, "视频时长已更新").put("room", toRoomInfo(store.findRoom(roomKey)));
    }

    private Protocol.Response leaveRoom(ClientHandler handler, Protocol.Request request) {
        String oldRoom = handler.currentRoomKey;
        handler.currentRoomKey = null;
        if (oldRoom != null) {
            broadcastSystemChat(oldRoom, handler.user.nickname + " 离开房间");
            broadcastRoom(oldRoom);
        }
        return ok(request, "已离开房间");
    }

    private Protocol.Response control(ClientHandler handler, Protocol.Request request) throws StoreException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        RoomRecord room = requireRoom(handler, roomKey);
        if (room.hostId != handler.user.id) {
            throw new IllegalArgumentException("系统采用房主控制模式，只有房主可以控制播放");
        }
        VideoRecord video = currentVideo(room);
        long durationMs = video == null || video.durationMs <= 0 ? DEFAULT_VIDEO_DURATION_MS : video.durationMs;
        String action = stringValue(request, "action");
        long position = clamp(longValue(request, "positionMs"), 0, durationMs);
        boolean playing;
        long savedPosition;
        long savedAt;
        synchronized (room) {
            if ("play".equals(action)) {
                requireCurrentVideo(room);
                room.positionMs = position;
                room.playing = true;
                room.lastUpdatedAt = System.currentTimeMillis();
            } else if ("pause".equals(action)) {
                room.positionMs = position;
                room.playing = false;
                room.lastUpdatedAt = System.currentTimeMillis();
            } else if ("seek".equals(action)) {
                requireCurrentVideo(room);
                room.positionMs = position;
                room.lastUpdatedAt = System.currentTimeMillis();
            } else {
                throw new IllegalArgumentException("未知播放控制：" + action);
            }
            playing = room.playing;
            savedPosition = room.positionMs;
            savedAt = room.lastUpdatedAt;
        }
        store.saveRoomPlayback(roomKey, playing, savedPosition, savedAt);
        broadcastRoom(roomKey);
        return ok(request, "播放状态已同步");
    }

    private Protocol.Response chat(ClientHandler handler, Protocol.Request request) throws StoreException {
        String roomKey = stringValue(request, "roomKey").toUpperCase();
        requireRoom(handler, roomKey);
        String content = stringValue(request, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("聊天内容不能为空");
        }
        long now = System.currentTimeMillis();
        store.saveChat(roomKey, handler.user.id, handler.user.nickname, content, now);
        Protocol.ChatMessage msg = new Protocol.ChatMessage(roomKey, handler.user.nickname, content, now);
        broadcast(roomKey, new Protocol.Event(Protocol.EVENT_CHAT).put("message", msg));
        return ok(request, "发送成功");
    }

    private RoomRecord requireRoom(ClientHandler handler, String roomKey) throws StoreException {
        RoomRecord room = store.findRoom(roomKey);
        if (!roomKey.equals(handler.currentRoomKey)) {
            throw new IllegalArgumentException("当前用户不在该房间中");
        }
        return room;
    }

    private void requireRoomAccess(ClientHandler handler, String roomKey) throws StoreException {
        RoomRecord room = store.findRoom(roomKey);
        if (room.hostId == handler.user.id) {
            return;
        }
        for (RoomRecord userRoom : store.listRoomsForUser(handler.user.id)) {
            if (room.key.equals(userRoom.key)) {
                return;
            }
        }
        throw new IllegalArgumentException("当前用户无权访问该房间");
    }

    private void broadcastRoom(String roomKey) {
        try {
            RoomRecord room = store.findRoom(roomKey);
            broadcast(roomKey, new Protocol.Event(Protocol.EVENT_ROOM_UPDATED).put("room", toRoomInfo(room)));
        } catch (StoreException ignored) {
        }
    }

    private void broadcastSystemChat(String roomKey, String content) {
        Protocol.ChatMessage msg = new Protocol.ChatMessage(roomKey, "系统", content, System.currentTimeMillis());
        broadcast(roomKey, new Protocol.Event(Protocol.EVENT_CHAT).put("message", msg));
    }

    private void broadcast(String roomKey, Protocol.Event event) {
        for (ClientHandler client : clients) {
            if (roomKey.equals(client.currentRoomKey)) {
                client.send(event);
            }
        }
    }

    private int activeMemberCount(String roomKey) {
        int count = 0;
        for (ClientHandler client : clients) {
            if (roomKey.equals(client.currentRoomKey) && client.user != null) {
                count++;
            }
        }
        return count;
    }

    private boolean isInRoom(int userId, String roomKey) {
        for (ClientHandler client : clients) {
            if (roomKey.equals(client.currentRoomKey) && client.user != null && client.user.id == userId) {
                return true;
            }
        }
        return false;
    }

    private Protocol.RoomSummary toRoomSummary(RoomRecord room) throws StoreException {
        List<VideoRecord> videos = store.listRoomVideos(room.key);
        VideoRecord current = currentVideo(room);
        return new Protocol.RoomSummary(room.key, room.name, room.hostId, room.hostName,
                room.maxMembers, room.createdAt, videos.size(), current == null ? "" : current.name);
    }

    private Protocol.RoomInfo toRoomInfo(RoomRecord room) throws StoreException {
        Protocol.RoomInfo info;
        synchronized (room) {
            VideoRecord video = currentVideo(room);
            long durationMs = video == null ? 0 : video.durationMs;
            long position = currentPosition(room, durationMs);
            info = new Protocol.RoomInfo(room.key, room.name, room.hostId, room.hostName,
                    video == null ? 0 : video.id, video == null ? "" : video.name,
                    durationMs, room.maxMembers, room.playing, position, room.createdAt);
        }
        for (VideoRecord video : store.listRoomVideos(room.key)) {
            info.videos.add(toVideoInfo(video));
        }
        for (ClientHandler client : clients) {
            if (room.key.equals(client.currentRoomKey) && client.user != null) {
                info.members.add(new Protocol.MemberInfo(client.user.id, client.user.nickname,
                        client.user.id == room.hostId, true));
            }
        }
        return info;
    }

    private long currentPosition(RoomRecord room, long durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        long position = room.positionMs;
        if (room.playing) {
            position += System.currentTimeMillis() - room.lastUpdatedAt;
        }
        return clamp(position, 0, durationMs);
    }

    private VideoRecord currentVideo(RoomRecord room) throws StoreException {
        if (room.currentVideoId <= 0) {
            return null;
        }
        return store.findVideo(room.currentVideoId);
    }

    private void requireCurrentVideo(RoomRecord room) {
        if (room.currentVideoId <= 0) {
            throw new IllegalArgumentException("请先在房间视频库中上传或选择视频");
        }
    }

    private String generateKey() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        while (true) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                key.append(chars.charAt(random.nextInt(chars.length())));
            }
            String value = key.toString();
            try {
                store.findRoom(value);
            } catch (StoreException e) {
                return value;
            }
        }
    }

    private void requireLogin(ClientHandler handler) {
        if (handler.user == null) {
            throw new IllegalArgumentException("请先登录");
        }
    }

    private void deleteVideoFile(VideoRecord video) throws IOException {
        if (video.serverPath != null && !video.serverPath.isBlank()) {
            Files.deleteIfExists(Paths.get(video.serverPath));
        }
    }

    private static Protocol.User toUser(UserRecord user) {
        return new Protocol.User(user.id, user.username, user.nickname);
    }

    private static Protocol.VideoInfo toVideoInfo(VideoRecord video) {
        return new Protocol.VideoInfo(video.id, video.roomKey, video.uploaderId, video.uploaderName, video.name,
                video.size, video.durationMs, video.uploadedAt, video.serverPath);
    }

    private static Protocol.Response ok(Protocol.Request request, String message) {
        return new Protocol.Response(request.requestId, true, message);
    }

    private static Protocol.Response error(Protocol.Request request, String message) {
        return new Protocol.Response(request.requestId, false, message == null ? "请求失败" : message);
    }

    private static String stringValue(Protocol.Request request, String key) {
        Object value = request.payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Protocol.Request request, String key) {
        Object value = request.payload.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Protocol.Request request, String key) {
        Object value = request.payload.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeFileName(String name) {
        String cleaned = name.replace('\\', '_').replace('/', '_').trim();
        return cleaned.isBlank() ? "video.dat" : cleaned;
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;
        private ObjectOutputStream out;
        private UserRecord user;
        private String currentRoomKey;

        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (Socket s = socket;
                 ObjectOutputStream output = new ObjectOutputStream(s.getOutputStream());
                 ObjectInputStream input = new ObjectInputStream(s.getInputStream())) {
                this.out = output;
                while (true) {
                    Object obj = input.readObject();
                    if (obj instanceof Protocol.Request) {
                        Protocol.Request request = (Protocol.Request) obj;
                        send(handle(this, request));
                    }
                }
            } catch (EOFException ignored) {
                // Client closed normally.
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Client disconnected: " + e.getMessage());
            } finally {
                clients.remove(this);
                if (currentRoomKey != null && user != null) {
                    broadcastSystemChat(currentRoomKey, user.nickname + " 断开连接");
                    broadcastRoom(currentRoomKey);
                }
            }
        }

        private void send(Object obj) {
            try {
                synchronized (this) {
                    if (out != null) {
                        out.writeObject(obj);
                        out.flush();
                        out.reset();
                    }
                }
            } catch (IOException e) {
                System.out.println("Send failed: " + e.getMessage());
            }
        }
    }

    private static final class PortProcess {
        final long pid;
        final String commandLine;

        PortProcess(long pid, String commandLine) {
            this.pid = pid;
            this.commandLine = commandLine;
        }
    }

    private static final class StoreSelection {
        final DataStore store;
        final String message;

        StoreSelection(DataStore store, String message) {
            this.store = store;
            this.message = message;
        }
    }
}
