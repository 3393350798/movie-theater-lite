package movietheater.server;

import java.nio.file.Paths;

public final class DataStoreSmokeTest {
    private DataStoreSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String dataDir = args.length > 0 ? args[0] : "server-data-store-smoke";
        DataStore store = new FileDataStore(Paths.get(dataDir));
        String suffix = String.valueOf(System.currentTimeMillis()).substring(8);
        UserRecord user = store.register("admincheck" + suffix, PasswordUtil.hash("123"), "旧昵称");
        if (store.listUsers().isEmpty()) {
            throw new IllegalStateException("user list should not be empty");
        }
        store.updateUser(user.id, "新昵称", PasswordUtil.hash("456"));
        UserRecord login = store.authenticate("admincheck" + suffix, PasswordUtil.hash("456"));
        if (!"新昵称".equals(login.nickname)) {
            throw new IllegalStateException("nickname update failed");
        }
        store.createRoom("T" + suffix.substring(Math.max(0, suffix.length() - 5)), "管理测试房间",
                user.id, login.nickname, 4);
        if (store.listRooms().isEmpty()) {
            throw new IllegalStateException("room list should not be empty");
        }
        System.out.println("DATA STORE SMOKE TEST PASSED");
    }
}
