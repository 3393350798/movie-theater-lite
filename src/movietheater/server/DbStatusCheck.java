package movietheater.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public final class DbStatusCheck {
    private DbStatusCheck() {
    }

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        java.nio.file.Path file = java.nio.file.Paths.get("server.properties");
        if (java.nio.file.Files.exists(file)) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                props.load(in);
            }
        }
        String url = System.getProperty("db.url", props.getProperty("db.url", ""));
        String user = System.getProperty("db.user", props.getProperty("db.user", "root"));
        String password = System.getProperty("db.password", props.getProperty("db.password", ""));
        if (url.isBlank()) {
            throw new IllegalArgumentException("未配置 db.url");
        }
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            System.out.println("MySQL 连接成功：" + url);
            printCount(conn, "users");
            printCount(conn, "rooms");
            printCount(conn, "room_members");
            printCount(conn, "videos");
            printCount(conn, "chats");
        }
    }

    private static void printCount(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            System.out.println(table + " = " + rs.getInt(1));
        }
    }
}
