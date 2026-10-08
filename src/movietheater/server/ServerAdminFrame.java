package movietheater.server;

import movietheater.common.Protocol;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;

final class ServerAdminFrame extends JFrame {
    private final DataStore store;
    private final JLabel statusLabel = new JLabel("等待刷新");
    private final DefaultTableModel userModel = new DefaultTableModel(
            new Object[]{"编号", "用户名", "昵称", "注册时间"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable userTable = new JTable(userModel);
    private final DefaultTableModel roomModel = new DefaultTableModel(
            new Object[]{"分享 key", "房间名", "房主", "人数上限", "视频数", "当前视频", "播放状态"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable roomTable = new JTable(roomModel);

    ServerAdminFrame(DataStore store, String storageMessage) {
        super("多人影院系统 - 服务端管理");
        this.store = store;
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setSize(980, 620);
        setLocationByPlatform(true);
        setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        top.add(new JLabel(storageMessage), BorderLayout.CENTER);
        JButton refreshButton = new JButton("刷新数据");
        refreshButton.addActionListener(e -> refreshData());
        top.add(refreshButton, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel userPanel = new JPanel(new BorderLayout(6, 6));
        userPanel.setBorder(BorderFactory.createTitledBorder("用户管理"));
        userPanel.add(new JScrollPane(userTable), BorderLayout.CENTER);
        userPanel.add(buildUserActions(), BorderLayout.SOUTH);

        JPanel roomPanel = new JPanel(new BorderLayout(6, 6));
        roomPanel.setBorder(BorderFactory.createTitledBorder("房间概览"));
        roomPanel.add(new JScrollPane(roomTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, userPanel, roomPanel);
        split.setResizeWeight(0.52);
        add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        bottom.add(statusLabel, BorderLayout.CENTER);
        JButton demoButton = new JButton("异常场景演示");
        demoButton.addActionListener(e -> showExceptionDemoDialog());
        bottom.add(demoButton, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        refreshData();
    }

    private JPanel buildUserActions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton editNickname = new JButton("修改昵称");
        editNickname.addActionListener(e -> editNickname());
        JButton resetPassword = new JButton("重置密码");
        resetPassword.addActionListener(e -> resetPassword());
        panel.add(editNickname);
        panel.add(resetPassword);
        panel.add(new JLabel("修改后客户端重新登录即可生效"));
        return panel;
    }

    private void refreshData() {
        runAsync("刷新数据", () -> {
            List<UserRecord> users = store.listUsers();
            List<RoomRecord> rooms = store.listRooms();
            SwingUtilities.invokeLater(() -> {
                userModel.setRowCount(0);
                for (UserRecord user : users) {
                    userModel.addRow(new Object[]{
                            user.id,
                            user.username,
                            user.nickname,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(user.createdAt))
                    });
                }
                roomModel.setRowCount(0);
                for (RoomRecord room : rooms) {
                    String currentVideoName = "";
                    int videoCount = 0;
                    try {
                        List<VideoRecord> videos = store.listRoomVideos(room.key);
                        videoCount = videos.size();
                        for (VideoRecord video : videos) {
                            if (video.id == room.currentVideoId) {
                                currentVideoName = video.name;
                                break;
                            }
                        }
                    } catch (StoreException ignored) {
                    }
                    roomModel.addRow(new Object[]{
                            room.key,
                            room.name,
                            room.hostName,
                            room.maxMembers,
                            videoCount,
                            currentVideoName,
                            room.playing ? "播放中" : "已暂停 " + Protocol.formatDuration(room.positionMs)
                    });
                }
                statusLabel.setText("已刷新：用户 " + users.size() + " 个，房间 " + rooms.size() + " 个");
            });
        });
    }

    private void editNickname() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            warn("请先选择用户");
            return;
        }
        int modelRow = userTable.convertRowIndexToModel(row);
        int userId = ((Number) userModel.getValueAt(modelRow, 0)).intValue();
        String oldNickname = String.valueOf(userModel.getValueAt(modelRow, 2));
        JTextField field = new JTextField(oldNickname, 18);
        int choice = JOptionPane.showConfirmDialog(this, field, "修改昵称", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        String nickname = field.getText().trim();
        if (nickname.isBlank()) {
            warn("昵称不能为空");
            return;
        }
        runAsync("修改昵称", () -> {
            store.updateUser(userId, nickname, null);
            refreshData();
        });
    }

    private void resetPassword() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            warn("请先选择用户");
            return;
        }
        int modelRow = userTable.convertRowIndexToModel(row);
        int userId = ((Number) userModel.getValueAt(modelRow, 0)).intValue();
        String username = String.valueOf(userModel.getValueAt(modelRow, 1));
        JPasswordField passwordField = new JPasswordField(18);
        int choice = JOptionPane.showConfirmDialog(this, passwordField,
                "为用户 " + username + " 设置新密码", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        String password = new String(passwordField.getPassword());
        if (password.isBlank()) {
            warn("密码不能为空");
            return;
        }
        runAsync("重置密码", () -> {
            store.updateUser(userId, null, PasswordUtil.hash(password));
            refreshData();
        });
    }

    private void runAsync(String title, ThrowingRunnable runnable) {
        new Thread(() -> {
            try {
                runnable.run();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> warn(title + "失败：" + ex.getMessage()));
            }
        }, "server-admin-task").start();
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.WARNING_MESSAGE);
    }

    private void showExceptionDemoDialog() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JLabel note = new JLabel("<html>以下内容根据当前程序中的真实异常提示整理，"
                + "服务端启动和存储类错误输出到 CMD 窗口，客户端业务类错误按客户端弹窗样式展示。</html>");
        JPanel buttons = new JPanel(new GridLayout(0, 2, 8, 8));
        addConsoleDemoButton(buttons, "端口占用",
                "启动失败：端口 5000 已被占用。\n请关闭旧服务端，或修改 server.properties 中的 server.port。");
        addConsoleDemoButton(buttons, "MySQL 未配置",
                "MySQL 数据库连接失败：未配置 db.url\n是否现在填写数据库配置后重试？(y/N)：");
        addConsoleDemoButton(buttons, "MySQL 连接失败回退",
                "MySQL 连接失败，已切换为本地文件兼容模式：未配置 db.url");
        addConsoleDemoButton(buttons, "MySQL 驱动缺失",
                "MySQL 数据库连接失败：未找到 MySQL JDBC 驱动，请确认 lib/mysql-connector-j-9.4.0.jar 存在");
        addConsoleDemoButton(buttons, "本地数据读取失败",
                "读取本地数据文件失败。若这是旧版本数据，请删除 server-data/store.dat 后重新启动。");
        addDemoButton(buttons, "客户端连接失败",
                "连接服务器失败：Connection refused: connect");
        addDemoButton(buttons, "服务器连接中断",
                "服务器连接已关闭");
        addDemoButton(buttons, "VLCJ 依赖缺失",
                "当前客户端未检测到 VLCJ 依赖，无法使用 VLC 内嵌播放器。\n\n"
                        + "是否自动下载 VLCJ、VLCJ-Natives、JNA、SLF4J 依赖到项目 vlcj-lib 目录？\n\n"
                        + "下载完成后仍需要本机已安装 VLC 播放器，或设置 VLC_HOME 指向 VLC 安装目录。");
        addDemoButton(buttons, "VLC 播放器不可用",
                "已检测到 VLCJ Java 依赖，但未检测到可用于内嵌播放的 VLC。\n\n"
                        + "当前 Java：64 位\n"
                        + "未检测到 VLC 播放器本体。\n\n"
                        + "请安装与当前 Java 位数一致的 VLC，或设置环境变量 VLC_HOME 指向正确的 VLC 安装目录，例如：\n"
                        + "C:\\Program Files\\VideoLAN\\VLC\n\n"
                        + "是否打开 VideoLAN 官方 VLC 下载页面？");
        addDemoButton(buttons, "VLC 位数不匹配",
                "检测到的是 32 位 VLC 安装目录：C:\\Program Files (x86)\\VideoLAN\\VLC\n"
                        + "当前 Java 是 64 位，VLCJ 内嵌播放器要求 Java 和 VLC 位数一致。");
        addDemoButton(buttons, "登录用户不存在",
                "登录失败：用户不存在");
        addDemoButton(buttons, "登录密码错误",
                "登录失败：密码错误");
        addDemoButton(buttons, "用户名重复",
                "注册失败：用户名已存在");
        addDemoButton(buttons, "未连接服务器",
                "请先连接服务器");
        addDemoButton(buttons, "房间不存在",
                "加入房间失败：房间不存在");
        addDemoButton(buttons, "房间人数已满",
                "加入房间失败：房间人数已满");
        addDemoButton(buttons, "未进入房间",
                "请先进入房间");
        addDemoButton(buttons, "无权访问房间",
                "当前用户无权访问该房间");
        addDemoButton(buttons, "未选择视频",
                "请先在房间视频库中上传或选择视频");
        addDemoButton(buttons, "视频文件缺失",
                "服务器视频文件不存在");
        addDemoButton(buttons, "非房主控制",
                "系统采用房主控制模式，只有房主可以控制播放");
        addDemoButton(buttons, "聊天内容为空",
                "聊天内容不能为空");
        addDemoButton(buttons, "内嵌播放失败",
                "VLCJ 加载视频失败：请确认本机 VLC 可以播放该文件\n\n"
                        + "是否改用系统播放器打开当前视频？\n"
                        + "这通常是内嵌播放器解码或依赖配置限制，不代表文件不是标准 MP4。\n"
                        + "可以使用系统播放器作为备用播放方式。");
        panel.add(note, BorderLayout.NORTH);
        panel.add(buttons, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(this, panel, "异常场景演示", JOptionPane.PLAIN_MESSAGE);
    }

    private void addDemoButton(JPanel panel, String title, String message) {
        JButton button = new JButton(title);
        button.addActionListener(e -> JOptionPane.showMessageDialog(this, message,
                title, JOptionPane.WARNING_MESSAGE));
        panel.add(button);
    }

    private void addConsoleDemoButton(JPanel panel, String title, String message) {
        JButton button = new JButton(title);
        button.addActionListener(e -> printServerConsoleMessage(message));
        panel.add(button);
    }

    private void printServerConsoleMessage(String message) {
        for (String line : message.split("\\R", -1)) {
            System.err.println(line);
        }
        System.err.flush();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
