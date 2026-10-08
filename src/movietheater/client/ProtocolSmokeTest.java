package movietheater.client;

import movietheater.common.Protocol;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ProtocolSmokeTest {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
        TestClient a = new TestClient(host, port);
        TestClient b = new TestClient(host, port);
        String suffix = String.valueOf(System.currentTimeMillis()).substring(8);
        a.register("alice" + suffix, "123", "Alice");
        b.register("bob" + suffix, "123", "Bob");
        a.login("alice" + suffix, "123");
        b.login("bob" + suffix, "123");
        Protocol.RoomInfo room = a.createRoom("测试房间", 4);
        Protocol.VideoInfo video = a.upload(room.key, "demo.txt", "fake video bytes".getBytes(StandardCharsets.UTF_8), 120_000);
        a.selectVideo(room.key, video.id);
        b.joinRoom(room.key);
        a.chat(room.key, "你好，开始观影");
        a.control(room.key, "play", 0);
        Thread.sleep(500);
        a.updateDuration(room.key, video.id, 125_000);
        a.control(room.key, "pause", 10_000);
        TestClient c = new TestClient(host, port);
        c.register("carol" + suffix, "123", "Carol");
        c.login("carol" + suffix, "123");
        Protocol.RoomInfo joined = c.joinRoom(room.key);
        if (joined.playing) {
            throw new IllegalStateException("new member should inherit paused room state");
        }
        if (Math.abs(joined.positionMs - 10_000) > 1000) {
            throw new IllegalStateException("new member pause position mismatch: " + joined.positionMs);
        }
        a.close();
        b.close();
        c.close();
        System.out.println("SMOKE TEST PASSED roomKey=" + room.key);
    }

    private static final class TestClient {
        private final Socket socket;
        private final ObjectOutputStream out;
        private final ObjectInputStream in;
        private final AtomicLong ids = new AtomicLong(1);
        private final Map<Long, Protocol.Response> responses = new ConcurrentHashMap<>();

        TestClient(String host, int port) throws Exception {
            socket = new Socket(host, port);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            Thread listener = new Thread(this::listen, "smoke-listener");
            listener.setDaemon(true);
            listener.start();
        }

        void register(String username, String password, String nickname) throws Exception {
            request(Protocol.REGISTER)
                    .put("username", username)
                    .put("password", password)
                    .put("nickname", nickname)
                    .send();
        }

        void login(String username, String password) throws Exception {
            request(Protocol.LOGIN)
                    .put("username", username)
                    .put("password", password)
                    .send();
        }

        Protocol.RoomInfo createRoom(String roomName, int maxMembers) throws Exception {
            Protocol.Response response = request(Protocol.CREATE_ROOM)
                    .put("roomName", roomName)
                    .put("maxMembers", maxMembers)
                    .send();
            return (Protocol.RoomInfo) response.payload.get("room");
        }

        Protocol.VideoInfo upload(String roomKey, String name, byte[] bytes, long durationMs) throws Exception {
            Protocol.Response response = request(Protocol.UPLOAD_VIDEO)
                    .put("roomKey", roomKey)
                    .put("fileName", name)
                    .put("bytes", bytes)
                    .put("durationMs", durationMs)
                    .send();
            return (Protocol.VideoInfo) response.payload.get("video");
        }

        void selectVideo(String roomKey, int videoId) throws Exception {
            request(Protocol.SELECT_VIDEO)
                    .put("roomKey", roomKey)
                    .put("videoId", videoId)
                    .send();
        }

        void updateDuration(String roomKey, int videoId, long durationMs) throws Exception {
            request(Protocol.UPDATE_VIDEO_DURATION)
                    .put("roomKey", roomKey)
                    .put("videoId", videoId)
                    .put("durationMs", durationMs)
                    .send();
        }

        Protocol.RoomInfo joinRoom(String key) throws Exception {
            Protocol.Response response = request(Protocol.JOIN_ROOM).put("key", key).send();
            return (Protocol.RoomInfo) response.payload.get("room");
        }

        void chat(String roomKey, String content) throws Exception {
            request(Protocol.CHAT).put("roomKey", roomKey).put("content", content).send();
        }

        void control(String roomKey, String action, long positionMs) throws Exception {
            request(Protocol.CONTROL)
                    .put("roomKey", roomKey)
                    .put("action", action)
                    .put("positionMs", positionMs)
                    .send();
        }

        Pending request(String type) {
            return new Pending(new Protocol.Request(ids.getAndIncrement(), type));
        }

        void close() throws Exception {
            socket.close();
        }

        private void listen() {
            try {
                while (true) {
                    Object obj = in.readObject();
                    if (obj instanceof Protocol.Response) {
                        Protocol.Response response = (Protocol.Response) obj;
                        responses.put(response.requestId, response);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private final class Pending {
            private final Protocol.Request request;

            Pending(Protocol.Request request) {
                this.request = request;
            }

            Pending put(String key, Object value) {
                request.put(key, value);
                return this;
            }

            Protocol.Response send() throws Exception {
                synchronized (out) {
                    out.writeObject(request);
                    out.flush();
                    out.reset();
                }
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    Protocol.Response response = responses.remove(request.requestId);
                    if (response != null) {
                        if (!response.ok) {
                            throw new IllegalStateException(response.message);
                        }
                        return response;
                    }
                    Thread.sleep(20);
                }
                throw new IllegalStateException("request timeout: " + request.type);
            }
        }
    }
}
