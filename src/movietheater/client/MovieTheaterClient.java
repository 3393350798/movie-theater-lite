package movietheater.client;

import movietheater.common.Protocol;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JTree;
import javax.swing.WindowConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MovieTheaterClient extends JFrame {
    private static final long DEFAULT_VIDEO_DURATION_MS = 10 * 60 * 1000L;
    private static final long SEEK_END_GUARD_MS = 500L;

    private final JTextField hostField = new JTextField("127.0.0.1", 12);
    private final JTextField portField = new JTextField("5050", 5);
    private final JTextField usernameField = new JTextField(12);
    private final JPasswordField passwordField = new JPasswordField(12);
    private final JTextField nicknameField = new JTextField(12);
    private final JLabel userLabel = new JLabel("未登录");
    private final DefaultMutableTreeNode libraryRoot = new DefaultMutableTreeNode("我的房间");
    private final DefaultTreeModel libraryTreeModel = new DefaultTreeModel(libraryRoot);
    private final JTree libraryTree = new JTree(libraryTreeModel);
    private final JPanel libraryPanel = new JPanel(new BorderLayout(6, 6));
    private final JLabel libraryTitle = new JLabel("我的房间");
    private final JTextField roomNameField = new JTextField("我的观影房间", 12);
    private final JTextField maxMembersField = new JTextField("4", 4);
    private final JTextField joinKeyField = new JTextField(8);
    private final JButton refreshButton = new JButton("刷新");
    private final JButton uploadButton = new JButton("上传视频");
    private final JButton deleteButton = new JButton("删除");
    private final JButton downloadButton = new JButton("下载/播放视频");
    private final JButton createRoomButton = new JButton("创建房间");
    private final JButton joinRoomButton = new JButton("加入房间");
    private final JButton leaveRoomButton = new JButton("返回房间列表");
    private final JButton selectVideoButton = new JButton("设为当前视频");
    private final JLabel roomLabel = new JLabel("未进入房间");
    private final JLabel videoLabel = new JLabel("未选择视频");
    private final JLabel positionLabel = new JLabel("00:00 / 00:00");
    private final JSlider progressSlider = new JSlider(0, 1000, 0);
    private final JButton playButton = new JButton("播放");
    private final JButton pauseButton = new JButton("暂停");
    private final JButton syncButton = new JButton("同步进度");
    private final JLabel playerStatusLabel = new JLabel("等待加载视频");
    private final DefaultListModel<Protocol.MemberInfo> memberModel = new DefaultListModel<>();
    private final JList<Protocol.MemberInfo> memberList = new JList<>(memberModel);
    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatField = new JTextField();
    private final VideoPlayerBackend videoPlayer = new CompositeVideoPlayer();

    private final AtomicLong requestSeq = new AtomicLong(1);
    private final Map<Long, ResponseWaiter> waiters = new ConcurrentHashMap<>();
    private ObjectOutputStream out;
    private Socket socket;
    private Protocol.User currentUser;
    private Protocol.RoomInfo currentRoom;
    private long roomInfoReceivedAt;
    private boolean draggingSlider;
    private boolean applyingRemoteState;
    private boolean noVideoTrackWarned;
    private boolean roomVideoLoading;
    private boolean durationUpdateInFlight;
    private Path lastFallbackPromptFile;
    private String previewVideoName;
    private long previewDurationMs;
    private long previewStartedAt;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MovieTheaterClient client = new MovieTheaterClient();
            client.setVisible(true);
        });
    }

    private MovieTheaterClient() {
        super("多人影院系统 - 客户端");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1040, 680));
        setLayout(new BorderLayout(8, 8));
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildMainPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
        bindActions();
        videoPlayer.setStatusListener(message -> SwingUtilities.invokeLater(() -> handlePlayerStatus(message)));
        new Timer(500, e -> refreshProgress()).start();
        updateRoomControls();
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildTopPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("连接与登录"));
        panel.add(new JLabel("服务器"));
        panel.add(hostField);
        panel.add(new JLabel("端口"));
        panel.add(portField);
        panel.add(button("连接", this::connect));
        panel.add(new JLabel("用户名"));
        panel.add(usernameField);
        panel.add(new JLabel("密码"));
        panel.add(passwordField);
        panel.add(new JLabel("昵称"));
        panel.add(nicknameField);
        panel.add(button("注册", this::register));
        panel.add(button("登录", this::login));
        panel.add(userLabel);
        return panel;
    }

    private JPanel buildMainPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.add(buildLibraryPanel(), BorderLayout.WEST);
        root.add(buildTheaterPanel(), BorderLayout.CENTER);
        return root;
    }

    private JPanel buildLibraryPanel() {
        libraryPanel.setPreferredSize(new Dimension(340, 460));
        libraryPanel.setBorder(BorderFactory.createTitledBorder("房间管理"));
        libraryTree.setRootVisible(true);
        libraryTree.setShowsRootHandles(true);
        libraryTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        libraryPanel.add(libraryTitle, BorderLayout.NORTH);
        libraryPanel.add(new JScrollPane(libraryTree), BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        actions.add(refreshButton, c);
        c.gridx = 1;
        actions.add(uploadButton, c);
        c.gridx = 0;
        c.gridy = 1;
        actions.add(deleteButton, c);
        c.gridx = 1;
        actions.add(downloadButton, c);
        c.gridx = 0;
        c.gridy = 2;
        actions.add(selectVideoButton, c);
        c.gridx = 1;
        actions.add(leaveRoomButton, c);
        c.gridx = 0;
        c.gridy = 3;
        actions.add(new JLabel("房间名"), c);
        c.gridx = 1;
        actions.add(roomNameField, c);
        c.gridx = 0;
        c.gridy = 4;
        actions.add(new JLabel("人数上限"), c);
        c.gridx = 1;
        actions.add(maxMembersField, c);
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        actions.add(createRoomButton, c);
        c.gridy = 6;
        c.gridwidth = 1;
        actions.add(new JLabel("分享 key"), c);
        c.gridx = 1;
        actions.add(joinKeyField, c);
        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 2;
        actions.add(joinRoomButton, c);
        libraryPanel.add(actions, BorderLayout.SOUTH);
        return libraryPanel;
    }

    private JPanel buildTheaterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("同步观影房间"));

        JPanel playerPanel = new JPanel(new BorderLayout(6, 6));
        JPanel infoPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        infoPanel.add(new JLabel("房间："), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        infoPanel.add(roomLabel, c);
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        infoPanel.add(new JLabel("视频："), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        infoPanel.add(videoLabel, c);
        playerPanel.add(infoPanel, BorderLayout.NORTH);

        JPanel videoArea = new JPanel(new BorderLayout());
        videoArea.setBorder(BorderFactory.createTitledBorder("视频播放区"));
        videoArea.add(videoPlayer.getComponent(), BorderLayout.CENTER);
        videoArea.add(playerStatusLabel, BorderLayout.SOUTH);
        playerPanel.add(videoArea, BorderLayout.CENTER);

        progressSlider.setPaintTicks(true);
        progressSlider.setPaintLabels(false);
        progressSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                draggingSlider = true;
                updateSliderValueFromMouse(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                updateSliderValueFromMouse(e);
                draggingSlider = false;
                sendSeek();
            }
        });
        progressSlider.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateProgressTooltip(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                updateSliderValueFromMouse(e);
                updateProgressTooltip(e);
            }
        });
        JPanel progressPanel = new JPanel(new BorderLayout(6, 6));
        progressPanel.add(progressSlider, BorderLayout.CENTER);
        progressPanel.add(positionLabel, BorderLayout.EAST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(playButton);
        controls.add(pauseButton);
        controls.add(syncButton);
        controls.add(new JLabel(videoPlayer.isAvailable()
                ? "系统采用房主控制模式，当前内嵌播放器：" + videoPlayer.backendName()
                : "未加载 VLCJ/JavaFX 时可用系统播放器打开视频"));
        JPanel bottomControls = new JPanel(new BorderLayout(4, 4));
        bottomControls.add(progressPanel, BorderLayout.NORTH);
        bottomControls.add(controls, BorderLayout.SOUTH);
        playerPanel.add(bottomControls, BorderLayout.SOUTH);

        memberList.setBorder(BorderFactory.createTitledBorder("成员"));
        memberList.setPreferredSize(new Dimension(180, 200));
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, playerPanel, new JScrollPane(memberList));
        split.setResizeWeight(1.0);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("文字聊天"));
        panel.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        JPanel sendPanel = new JPanel(new BorderLayout(6, 6));
        sendPanel.add(chatField, BorderLayout.CENTER);
        sendPanel.add(button("发送", this::sendChat), BorderLayout.EAST);
        panel.add(sendPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton button(String text, java.util.function.Consumer<ActionEvent> action) {
        JButton button = new JButton(text);
        button.addActionListener(action::accept);
        return button;
    }

    private void bindActions() {
        refreshButton.addActionListener(this::refreshLibrary);
        uploadButton.addActionListener(this::uploadVideo);
        deleteButton.addActionListener(this::deleteSelected);
        downloadButton.addActionListener(this::downloadSelectedOrRoomVideo);
        createRoomButton.addActionListener(this::createRoom);
        joinRoomButton.addActionListener(this::joinRoom);
        leaveRoomButton.addActionListener(this::leaveRoom);
        selectVideoButton.addActionListener(this::selectCurrentVideo);
        playButton.addActionListener(e -> handlePlay());
        pauseButton.addActionListener(e -> handlePause());
        syncButton.addActionListener(e -> handleSeek());
        chatField.addActionListener(e -> sendChat(e));
        libraryTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    handleLibraryDoubleClick();
                }
            }
        });
    }

    private void connect(ActionEvent e) {
        runAsync("连接服务器", () -> {
            closeSocket();
            socket = new Socket(hostField.getText().trim(), Integer.parseInt(portField.getText().trim()));
            out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream input = new ObjectInputStream(socket.getInputStream());
            Thread listener = new Thread(() -> listen(input), "server-listener");
            listener.setDaemon(true);
            listener.start();
            info("连接成功");
        });
    }

    private void register(ActionEvent e) {
        if (!ensureConnected()) {
            return;
        }
        runAsync("注册", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.REGISTER)
                    .put("username", usernameField.getText())
                    .put("password", new String(passwordField.getPassword()))
                    .put("nickname", nicknameField.getText()));
            requireOk(response);
            info(response.message);
        });
    }

    private void login(ActionEvent e) {
        if (!ensureConnected()) {
            return;
        }
        runAsync("登录", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.LOGIN)
                    .put("username", usernameField.getText())
                    .put("password", new String(passwordField.getPassword())));
            requireOk(response);
            currentUser = (Protocol.User) response.payload.get("user");
            SwingUtilities.invokeLater(() -> userLabel.setText("当前用户：" + currentUser.nickname));
            loadRooms();
        });
    }

    private void refreshLibrary(ActionEvent e) {
        if (currentRoom == null) {
            loadRooms();
        } else {
            loadRoomVideos(currentRoom.key);
        }
    }

    private void loadRooms() {
        if (!ensureConnected()) {
            return;
        }
        runAsync("刷新房间列表", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.LIST_ROOMS));
            requireOk(response);
            @SuppressWarnings("unchecked")
            List<Protocol.RoomSummary> rooms = (List<Protocol.RoomSummary>) response.payload.getOrDefault("rooms", new ArrayList<>());
            List<RoomTreeNode> roomNodes = new ArrayList<>();
            for (Protocol.RoomSummary room : rooms) {
                roomNodes.add(new RoomTreeNode(room, fetchRoomVideos(room.key)));
            }
            SwingUtilities.invokeLater(() -> {
                currentRoom = null;
                roomInfoReceivedAt = 0;
                previewVideoName = null;
                libraryTitle.setText("我的房间");
                setLibraryRooms(roomNodes);
                roomLabel.setText("未进入房间");
                videoLabel.setText("未选择视频");
                memberModel.clear();
                updateRoomControls();
            });
        });
    }

    private List<Protocol.VideoInfo> fetchRoomVideos(String roomKey) throws Exception {
        Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.LIST_ROOM_VIDEOS)
                .put("roomKey", roomKey));
        requireOk(response);
        @SuppressWarnings("unchecked")
        List<Protocol.VideoInfo> videos =
                (List<Protocol.VideoInfo>) response.payload.getOrDefault("videos", new ArrayList<>());
        return videos;
    }

    private void loadRoomVideos(String roomKey) {
        if (!ensureConnected()) {
            return;
        }
        runAsync("刷新房间视频库", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.LIST_ROOM_VIDEOS)
                    .put("roomKey", roomKey));
            requireOk(response);
            @SuppressWarnings("unchecked")
            List<Protocol.VideoInfo> videos = (List<Protocol.VideoInfo>) response.payload.getOrDefault("videos", new ArrayList<>());
            SwingUtilities.invokeLater(() -> setLibraryVideos(videos));
        });
    }

    private void setLibraryVideos(List<Protocol.VideoInfo> videos) {
        libraryTitle.setText(currentRoom == null ? "房间视频库" : "房间视频库：" + currentRoom.name);
        libraryRoot.setUserObject(currentRoom == null ? "房间视频库" : "房间视频库：" + currentRoom.name);
        libraryRoot.removeAllChildren();
        for (Protocol.VideoInfo video : videos) {
            libraryRoot.add(new DefaultMutableTreeNode(video));
        }
        libraryTreeModel.reload();
        expandRoot();
    }

    private void setLibraryRooms(List<RoomTreeNode> rooms) {
        libraryRoot.setUserObject("我的房间");
        libraryRoot.removeAllChildren();
        DefaultMutableTreeNode createdRoot = new DefaultMutableTreeNode("我创建的房间");
        DefaultMutableTreeNode joinedRoot = new DefaultMutableTreeNode("我加入的房间");
        for (RoomTreeNode roomNode : rooms) {
            DefaultMutableTreeNode roomTreeNode = new DefaultMutableTreeNode(roomNode.room);
            for (Protocol.VideoInfo video : roomNode.videos) {
                roomTreeNode.add(new DefaultMutableTreeNode(video));
            }
            if (currentUser != null && roomNode.room.hostId == currentUser.id) {
                createdRoot.add(roomTreeNode);
            } else {
                joinedRoot.add(roomTreeNode);
            }
        }
        libraryRoot.add(createdRoot);
        libraryRoot.add(joinedRoot);
        libraryTreeModel.reload();
        expandLibraryGroups();
    }

    private void expandRoot() {
        libraryTree.expandPath(new TreePath(libraryRoot.getPath()));
    }

    private void expandLibraryGroups() {
        expandRoot();
        for (int i = 0; i < libraryRoot.getChildCount(); i++) {
            Object child = libraryRoot.getChildAt(i);
            if (child instanceof DefaultMutableTreeNode) {
                libraryTree.expandPath(new TreePath(((DefaultMutableTreeNode) child).getPath()));
            }
        }
    }

    private void createRoom(ActionEvent e) {
        if (!ensureConnected()) {
            return;
        }
        runAsync("创建房间", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.CREATE_ROOM)
                    .put("roomName", roomNameField.getText())
                    .put("maxMembers", Integer.parseInt(maxMembersField.getText().trim())));
            requireOk(response);
            Protocol.RoomInfo room = (Protocol.RoomInfo) response.payload.get("room");
            SwingUtilities.invokeLater(() -> joinKeyField.setText(room.key));
            applyRoom(room);
            info("房间创建成功，分享 key：" + room.key + "\n进入房间后可上传视频到该房间的视频库。");
        });
    }

    private void joinRoom(ActionEvent e) {
        if (!ensureConnected()) {
            return;
        }
        runAsync("加入房间", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.JOIN_ROOM)
                    .put("key", joinKeyField.getText()));
            requireOk(response);
            applyRoom((Protocol.RoomInfo) response.payload.get("room"));
            info("加入房间成功");
        });
    }

    private void leaveRoom(ActionEvent e) {
        if (currentRoom == null) {
            loadRooms();
            return;
        }
        runAsync("返回房间列表", () -> {
            try {
                sendRequest(new Protocol.Request(nextRequestId(), Protocol.LEAVE_ROOM));
            } catch (Exception ignored) {
            }
            SwingUtilities.invokeLater(() -> {
                currentRoom = null;
                previewVideoName = null;
                chatArea.setText("");
            });
            loadRooms();
        });
    }

    private void uploadVideo(ActionEvent e) {
        if (!ensureConnected()) {
            return;
        }
        if (currentRoom == null) {
            warn("请先创建或进入房间，再上传视频到房间视频库");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        runAsync("上传视频", () -> {
            long durationMs = detectVideoDurationMs(file);
            byte[] bytes = Files.readAllBytes(file);
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.UPLOAD_VIDEO)
                    .put("roomKey", currentRoom.key)
                    .put("fileName", file.getFileName().toString())
                    .put("durationMs", durationMs)
                    .put("bytes", bytes));
            requireOk(response);
            applyRoom((Protocol.RoomInfo) response.payload.get("room"));
            info("上传成功，视频时长：" + Protocol.formatDuration(durationMs));
        });
    }

    private void deleteSelected(ActionEvent e) {
        Object selected = selectedLibraryObject();
        if (selected == null) {
            warn("请先选择要删除的项目");
            return;
        }
        if (selected instanceof Protocol.RoomSummary) {
            deleteRoom((Protocol.RoomSummary) selected);
        } else if (selected instanceof Protocol.VideoInfo) {
            deleteVideo((Protocol.VideoInfo) selected);
        }
    }

    private void deleteRoom(Protocol.RoomSummary room) {
        if (currentUser == null || room.hostId != currentUser.id) {
            warn("只有房主可以删除房间");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "确认删除房间：" + room.name + "？\n房间内视频也会从服务器删除。",
                "删除房间", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        runAsync("删除房间", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.DELETE_ROOM)
                    .put("roomKey", room.key));
            requireOk(response);
            loadRooms();
        });
    }

    private void deleteVideo(Protocol.VideoInfo video) {
        if (currentRoom == null) {
            warn("请先进入房间");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "确认删除视频：" + video.name + "？", "删除视频",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        runAsync("删除视频", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.DELETE_VIDEO)
                    .put("roomKey", currentRoom.key)
                    .put("videoId", video.id));
            requireOk(response);
            applyRoom((Protocol.RoomInfo) response.payload.get("room"));
        });
    }

    private void selectCurrentVideo(ActionEvent e) {
        Protocol.VideoInfo video = selectedVideo();
        if (video == null) {
            warn("请先在房间视频库中选择视频");
            return;
        }
        if (!isHost()) {
            warn("只有房主可以切换当前播放视频");
            return;
        }
        runAsync("切换当前视频", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.SELECT_VIDEO)
                    .put("roomKey", currentRoom.key)
                    .put("videoId", video.id));
            requireOk(response);
            applyRoom((Protocol.RoomInfo) response.payload.get("room"));
        });
    }

    private void handleLibraryDoubleClick() {
        Object selected = selectedLibraryObject();
        if (selected instanceof Protocol.RoomSummary) {
            joinKeyField.setText(((Protocol.RoomSummary) selected).key);
            joinRoom(null);
        } else if (selected instanceof Protocol.VideoInfo) {
            Protocol.VideoInfo video = (Protocol.VideoInfo) selected;
            if (currentRoom != null && isHost() && currentRoom.currentVideoId != video.id) {
                selectCurrentVideo(null);
            } else {
                playVideoInCenter(video.id, video.name, video.durationMs);
            }
        }
    }

    private void sendControl(String action) {
        if (currentRoom == null) {
            warn("请先进入房间");
            return;
        }
        if (!isHost()) {
            warn("系统采用房主控制模式，只有房主可以控制播放");
            return;
        }
        if (currentRoom.currentVideoId <= 0) {
            warn("请先在房间视频库中上传或选择视频");
            return;
        }
        long position = sliderPositionMs();
        runAsync("同步播放控制", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.CONTROL)
                    .put("roomKey", currentRoom.key)
                    .put("action", action)
                    .put("positionMs", position));
            requireOk(response);
        });
    }

    private void handlePlay() {
        if (isPreviewActive()) {
            videoPlayer.play();
            if (previewStartedAt <= 0) {
                previewStartedAt = System.currentTimeMillis();
            }
            return;
        }
        sendControl("play");
    }

    private void handlePause() {
        if (isPreviewActive()) {
            videoPlayer.pause();
            refreshPreviewProgress();
            return;
        }
        sendControl("pause");
    }

    private void handleSeek() {
        if (isPreviewActive()) {
            seekPreviewToSlider();
            return;
        }
        sendControl("seek");
    }

    private void sendSeek() {
        if (isPreviewActive()) {
            seekPreviewToSlider();
        } else if (currentRoom != null && isHost()) {
            sendControl("seek");
        }
    }

    private void seekPreviewToSlider() {
        if (!isPreviewActive()) {
            return;
        }
        long position = clampSeekPosition(previewSliderPositionMs(), previewDurationMs);
        videoPlayer.seek(position);
        previewStartedAt = System.currentTimeMillis() - position;
        refreshPreviewProgress();
    }

    private void sendChat(ActionEvent e) {
        if (currentRoom == null) {
            warn("请先进入房间");
            return;
        }
        String content = chatField.getText().trim();
        if (content.isEmpty()) {
            return;
        }
        chatField.setText("");
        runAsync("发送聊天", () -> {
            Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.CHAT)
                    .put("roomKey", currentRoom.key)
                    .put("content", content));
            requireOk(response);
        });
    }

    private void downloadSelectedOrRoomVideo(ActionEvent e) {
        if (!ensureConnected()) {
            return;
        }
        Protocol.VideoInfo selected = selectedVideo();
        int videoId;
        String videoName;
        long durationMs;
        if (selected != null) {
            videoId = selected.id;
            videoName = selected.name;
            durationMs = selected.durationMs;
        } else if (currentRoom != null && currentRoom.currentVideoId > 0) {
            videoId = currentRoom.currentVideoId;
            videoName = currentRoom.currentVideoName;
            durationMs = currentRoom.durationMs;
        } else {
            warn("请先选择视频或进入有视频的房间");
            return;
        }
        playVideoInCenter(videoId, videoName, durationMs);
    }

    private void playVideoInCenter(int videoId, String videoName, long durationMs) {
        runAsync("下载并播放视频", () -> {
            Path target = downloadVideoToCache(videoId);
            if (target == null) {
                return;
            }
            if (!videoPlayer.isAvailable()) {
                info("未检测到内嵌播放器，已用系统播放器打开：" + target.toAbsolutePath());
                openInSystemPlayer(target);
                return;
            }
            videoPlayer.load(target);
            long actualDurationMs = detectPlayableDurationMs(target, durationMs);
            if (currentRoom != null) {
                previewVideoName = null;
                previewDurationMs = 0;
                previewStartedAt = 0;
                if (currentRoom.currentVideoId == videoId) {
                    maybeReportCurrentVideoDuration(actualDurationMs);
                    syncLocalPlayback(currentRoom);
                } else if (videoPlayer.isPlaying()) {
                    videoPlayer.pause();
                }
            } else {
                previewVideoName = videoName;
                previewDurationMs = actualDurationMs;
                previewStartedAt = System.currentTimeMillis();
            }
            SwingUtilities.invokeLater(() -> {
                videoLabel.setText(videoName + (currentRoom == null ? "（本地预览）" : ""));
                if (currentRoom == null) {
                    roomLabel.setText("未进入房间 / 本地预览");
                    updateRoomControls();
                    refreshPreviewProgress();
                }
            });
        });
    }

    private void listen(ObjectInputStream input) {
        try {
            while (true) {
                Object obj = input.readObject();
                if (obj instanceof Protocol.Response) {
                    Protocol.Response response = (Protocol.Response) obj;
                    ResponseWaiter waiter = waiters.remove(response.requestId);
                    if (waiter != null) {
                        waiter.complete(response);
                    }
                } else if (obj instanceof Protocol.Event) {
                    handleEvent((Protocol.Event) obj);
                }
            }
        } catch (EOFException ignored) {
            warn("服务器连接已关闭");
        } catch (IOException | ClassNotFoundException e) {
            warn("服务器连接异常：" + e.getMessage());
        }
    }

    private void handleEvent(Protocol.Event event) {
        if (Protocol.EVENT_ROOM_UPDATED.equals(event.type)) {
            Object room = event.payload.get("room");
            if (room instanceof Protocol.RoomInfo) {
                applyRoom((Protocol.RoomInfo) room);
            } else {
                SwingUtilities.invokeLater(() -> {
                    warn("当前房间已被删除");
                    currentRoom = null;
                    loadRooms();
                });
            }
        } else if (Protocol.EVENT_CHAT.equals(event.type)) {
            Protocol.ChatMessage message = (Protocol.ChatMessage) event.payload.get("message");
            SwingUtilities.invokeLater(() -> chatArea.append("[" + Protocol.formatTime(message.timestamp) + "] "
                    + message.sender + "：" + message.content + "\n"));
        }
    }

    private void applyRoom(Protocol.RoomInfo room) {
        currentRoom = room;
        roomInfoReceivedAt = System.currentTimeMillis();
        SwingUtilities.invokeLater(() -> {
            roomLabel.setText(room.name + " / key=" + room.key + " / 房主=" + room.hostName);
            videoLabel.setText(room.currentVideoName == null || room.currentVideoName.isBlank()
                    ? "房间视频库暂无当前视频" : room.currentVideoName);
            memberModel.clear();
            for (Protocol.MemberInfo member : room.members) {
                memberModel.addElement(member);
            }
            setLibraryVideos(room.videos);
            updateRoomControls();
            refreshProgress();
        });
        ensureRoomVideoLoaded(room);
        syncLocalPlayback(room);
    }

    private void updateRoomControls() {
        boolean inRoom = currentRoom != null;
        boolean host = isHost();
        boolean preview = isPreviewActive();
        refreshButton.setText(inRoom ? "刷新视频库" : "刷新房间");
        uploadButton.setEnabled(inRoom);
        selectVideoButton.setEnabled(inRoom && host);
        leaveRoomButton.setEnabled(inRoom);
        createRoomButton.setEnabled(!inRoom);
        joinRoomButton.setEnabled(!inRoom);
        roomNameField.setEnabled(!inRoom);
        maxMembersField.setEnabled(!inRoom);
        deleteButton.setEnabled(true);
        downloadButton.setEnabled(inRoom || selectedVideo() != null);
        playButton.setEnabled((inRoom && host && currentRoom.currentVideoId > 0) || preview);
        pauseButton.setEnabled((inRoom && host && currentRoom.currentVideoId > 0) || preview);
        syncButton.setEnabled((inRoom && host && currentRoom.currentVideoId > 0) || preview);
        progressSlider.setEnabled((inRoom && host && currentRoom.currentVideoId > 0) || preview);
        chatField.setEnabled(inRoom);
    }

    private boolean isHost() {
        return currentUser != null && currentRoom != null && currentRoom.hostId == currentUser.id;
    }

    private boolean isPreviewActive() {
        return currentRoom == null && previewVideoName != null
                && videoPlayer.isAvailable() && videoPlayer.getCurrentFile() != null;
    }

    private Protocol.VideoInfo selectedVideo() {
        Object selected = selectedLibraryObject();
        return selected instanceof Protocol.VideoInfo ? (Protocol.VideoInfo) selected : null;
    }

    private Object selectedLibraryObject() {
        TreePath path = libraryTree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object node = path.getLastPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) node).getUserObject();
        return value instanceof String ? null : value;
    }

    private void refreshProgress() {
        if (currentRoom == null) {
            refreshPreviewProgress();
            return;
        }
        if (currentRoom.currentVideoId <= 0 || currentRoom.durationMs <= 0) {
            if (!draggingSlider) {
                progressSlider.setValue(0);
            }
            positionLabel.setText("00:00 / 00:00");
            return;
        }
        long durationMs = effectiveRoomDurationMs();
        long pos;
        if (videoPlayer.isAvailable() && videoPlayer.getCurrentFile() != null
                && videoPlayer.isCurrentFileForVideo(currentRoom.currentVideoId)) {
            long playerPosition = videoPlayer.currentPositionMs();
            pos = playerPosition >= 0 ? playerPosition : estimatedRoomPosition(durationMs);
            maybeReportCurrentVideoDuration(videoPlayer.durationMs());
        } else {
            pos = estimatedRoomPosition(durationMs);
        }
        pos = Math.max(0, Math.min(pos, durationMs));
        if (!draggingSlider) {
            int value = durationMs == 0 ? 0 : (int) (pos * 1000 / durationMs);
            progressSlider.setValue(value);
        }
        positionLabel.setText(Protocol.formatDuration(pos) + " / " + Protocol.formatDuration(durationMs)
                + (currentRoom.playing ? " 播放中" : " 已暂停"));
        adjustPlaybackDrift(pos, durationMs);
    }

    private void refreshPreviewProgress() {
        if (!videoPlayer.isAvailable() || videoPlayer.getCurrentFile() == null || previewVideoName == null) {
            return;
        }
        long playerDuration = videoPlayer.durationMs();
        if (playerDuration > 0 && Math.abs(playerDuration - previewDurationMs) > 1000) {
            previewDurationMs = playerDuration;
        }
        long pos = videoPlayer.currentPositionMs();
        if (pos < 0 && previewStartedAt > 0) {
            pos = System.currentTimeMillis() - previewStartedAt;
        }
        pos = Math.max(0, Math.min(pos, previewDurationMs));
        if (!draggingSlider) {
            int value = previewDurationMs == 0 ? 0 : (int) (pos * 1000 / previewDurationMs);
            progressSlider.setValue(value);
        }
        positionLabel.setText(Protocol.formatDuration(pos) + " / " + Protocol.formatDuration(previewDurationMs)
                + " 本地预览");
    }

    private long sliderPositionMs() {
        if (currentRoom == null) {
            return 0;
        }
        long durationMs = effectiveRoomDurationMs();
        return clampSeekPosition(durationMs * progressSlider.getValue() / 1000L, durationMs);
    }

    private long previewSliderPositionMs() {
        if (previewDurationMs <= 0) {
            return 0;
        }
        return clampSeekPosition(previewDurationMs * progressSlider.getValue() / 1000L, previewDurationMs);
    }

    private void updateSliderValueFromMouse(MouseEvent e) {
        int width = Math.max(1, progressSlider.getWidth());
        int x = Math.max(0, Math.min(e.getX(), width));
        int value = progressSlider.getMinimum()
                + (int) Math.round((double) x / width * (progressSlider.getMaximum() - progressSlider.getMinimum()));
        progressSlider.setValue(Math.max(progressSlider.getMinimum(), Math.min(value, progressSlider.getMaximum())));
    }

    private void updateProgressTooltip(MouseEvent e) {
        long durationMs = currentRoom != null ? effectiveRoomDurationMs() : previewDurationMs;
        if (durationMs <= 0) {
            progressSlider.setToolTipText(null);
            return;
        }
        int width = Math.max(1, progressSlider.getWidth());
        int x = Math.max(0, Math.min(e.getX(), width));
        long positionMs = clampSeekPosition((long) (durationMs * ((double) x / width)), durationMs);
        progressSlider.setToolTipText(Protocol.formatDuration(positionMs) + " / " + Protocol.formatDuration(durationMs));
    }

    private long estimatedRoomPosition(long durationMs) {
        if (currentRoom == null) {
            return 0;
        }
        return expectedRoomPosition(currentRoom, durationMs);
    }

    private long expectedRoomPosition(Protocol.RoomInfo room, long durationMs) {
        if (room == null) {
            return 0;
        }
        long pos = room.positionMs;
        if (room.playing) {
            pos += System.currentTimeMillis() - roomInfoReceivedAt;
        }
        return Math.max(0, Math.min(pos, durationMs));
    }

    private long effectiveRoomDurationMs() {
        if (currentRoom == null) {
            return 0;
        }
        long playerDuration = -1;
        if (videoPlayer.isAvailable() && videoPlayer.getCurrentFile() != null
                && videoPlayer.isCurrentFileForVideo(currentRoom.currentVideoId)) {
            playerDuration = videoPlayer.durationMs();
        }
        if (playerDuration > 0) {
            return playerDuration;
        }
        return currentRoom.durationMs > 0 ? currentRoom.durationMs : DEFAULT_VIDEO_DURATION_MS;
    }

    private void ensureRoomVideoLoaded(Protocol.RoomInfo room) {
        if (room == null || !videoPlayer.isAvailable() || room.currentVideoId <= 0 || roomVideoLoading) {
            return;
        }
        previewVideoName = null;
        previewDurationMs = 0;
        previewStartedAt = 0;
        if (videoPlayer.isCurrentFileForVideo(room.currentVideoId)) {
            return;
        }
        roomVideoLoading = true;
        runAsync("加载房间视频", () -> {
            try {
                Path target = downloadVideoToCache(room.currentVideoId);
                if (target != null) {
                    videoPlayer.load(target);
                    Thread.sleep(1200);
                    long actualDuration = detectPlayableDurationMs(target, room.durationMs);
                    maybeReportCurrentVideoDuration(actualDuration);
                    syncLocalPlayback(room);
                }
            } finally {
                roomVideoLoading = false;
            }
        });
    }

    private Path downloadVideoToCache(int videoId) throws Exception {
        Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.DOWNLOAD_VIDEO)
                .put("videoId", videoId));
        requireOk(response);
        String fileName = sanitizeFileName(String.valueOf(response.payload.get("fileName")));
        byte[] bytes = (byte[]) response.payload.get("bytes");
        Path cacheDir = Paths.get("client-cache");
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(videoId + "_" + fileName);
        if (!Files.exists(target) || Files.size(target) != bytes.length) {
            Files.write(target, bytes);
        }
        return target;
    }

    private void syncLocalPlayback(Protocol.RoomInfo room) {
        if (!videoPlayer.isAvailable() || room == null || room.currentVideoId <= 0 || applyingRemoteState) {
            return;
        }
        if (!videoPlayer.isCurrentFileForVideo(room.currentVideoId)) {
            ensureRoomVideoLoaded(room);
            return;
        }
        applyingRemoteState = true;
        try {
            long durationMs = effectiveRoomDurationMs();
            long pos = expectedRoomPosition(room, durationMs);
            videoPlayer.setPlaybackRate(1.0);
            if (room.playing) {
                videoPlayer.seek(clampSeekPosition(pos, durationMs));
                if (!videoPlayer.isPlaying()) {
                    videoPlayer.play();
                }
            } else {
                if (videoPlayer.isPlaying()) {
                    videoPlayer.pause();
                }
                long localPos = videoPlayer.currentPositionMs();
                if (localPos < 0 || Math.abs(localPos - pos) > 500) {
                    videoPlayer.seek(clampSeekPosition(pos, durationMs));
                }
                if (videoPlayer.isPlaying()) {
                    videoPlayer.pause();
                }
            }
        } finally {
            applyingRemoteState = false;
        }
    }

    private void adjustPlaybackDrift(long playerPositionMs, long durationMs) {
        if (!videoPlayer.isAvailable() || videoPlayer.getCurrentFile() == null || currentRoom == null
                || applyingRemoteState
                || !videoPlayer.isCurrentFileForVideo(currentRoom.currentVideoId)) {
            return;
        }
        long expected = estimatedRoomPosition(durationMs);
        long diff = expected - playerPositionMs;
        long absDiff = Math.abs(diff);
        if (!currentRoom.playing) {
            if (videoPlayer.isPlaying()) {
                videoPlayer.pause();
            }
            videoPlayer.setPlaybackRate(1.0);
            if (absDiff > 500) {
                videoPlayer.seek(clampSeekPosition(expected, durationMs));
                if (videoPlayer.isPlaying()) {
                    videoPlayer.pause();
                }
            }
            return;
        }
        if (absDiff > 3000) {
            videoPlayer.setPlaybackRate(1.0);
            videoPlayer.seek(clampSeekPosition(expected, durationMs));
        } else if (diff > 800) {
            videoPlayer.setPlaybackRate(1.10);
        } else if (diff < -800) {
            videoPlayer.setPlaybackRate(0.95);
        } else if (Math.abs(videoPlayer.playbackRate() - 1.0) > 0.01) {
            videoPlayer.setPlaybackRate(1.0);
        }
    }

    private void maybeReportCurrentVideoDuration(long durationMs) {
        if (currentRoom == null || currentRoom.currentVideoId <= 0 || durationMs <= 0 || durationUpdateInFlight) {
            return;
        }
        if (Math.abs(durationMs - currentRoom.durationMs) <= 1000) {
            return;
        }
        durationUpdateInFlight = true;
        String roomKey = currentRoom.key;
        int videoId = currentRoom.currentVideoId;
        runAsync("更新视频时长", () -> {
            try {
                Protocol.Response response = sendRequest(new Protocol.Request(nextRequestId(), Protocol.UPDATE_VIDEO_DURATION)
                        .put("roomKey", roomKey)
                        .put("videoId", videoId)
                        .put("durationMs", durationMs));
                requireOk(response);
                Object room = response.payload.get("room");
                if (room instanceof Protocol.RoomInfo) {
                    applyRoom((Protocol.RoomInfo) room);
                }
            } finally {
                durationUpdateInFlight = false;
            }
        });
    }

    private void handlePlayerStatus(String message) {
        playerStatusLabel.setText(message);
        if (message.startsWith("正在加载视频") || message.startsWith("正在用 VLC")) {
            noVideoTrackWarned = false;
            return;
        }
        if (shouldPromptFallbackPlayer(message) && !noVideoTrackWarned) {
            noVideoTrackWarned = true;
            promptFallbackPlayer(message);
        }
    }

    private boolean shouldPromptFallbackPlayer(String message) {
        return message.startsWith("已加载声音")
                || message.startsWith("视频播放错误")
                || message.startsWith("视频文件错误")
                || message.startsWith("视频加载失败")
                || (message.startsWith("VLCJ ") && message.contains("失败"));
    }

    private void promptFallbackPlayer(String message) {
        Path currentFile = videoPlayer.getCurrentFile();
        if (currentFile == null) {
            return;
        }
        if (currentFile.equals(lastFallbackPromptFile)) {
            return;
        }
        lastFallbackPromptFile = currentFile;
        int choice = JOptionPane.showConfirmDialog(this,
                message + "\n\n是否改用系统播放器打开当前视频？\n"
                        + "这通常是内嵌播放器解码或依赖配置限制，不代表文件不是标准 MP4。\n"
                        + "可以使用系统播放器作为备用播放方式。",
                "内嵌播放失败", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            openInSystemPlayer(currentFile);
        }
    }

    private void openInSystemPlayer(Path file) {
        if (file == null) {
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            warn("当前系统不支持自动打开播放器，请手动打开：" + file.toAbsolutePath());
            return;
        }
        try {
            Desktop.getDesktop().open(file.toFile());
        } catch (IOException ex) {
            warn("打开系统播放器失败：" + ex.getMessage());
        }
    }

    private long detectVideoDurationMs(Path file) {
        SwingUtilities.invokeLater(() -> playerStatusLabel.setText("正在读取视频真实时长：" + file.getFileName()));
        long durationMs = VideoMetadataReader.readDurationMs(file);
        if (durationMs > 0) {
            return durationMs;
        }
        if (videoPlayer.isAvailable()) {
            durationMs = videoPlayer.readDurationMs(file, 8000);
            if (durationMs > 0) {
                return durationMs;
            }
        }
        SwingUtilities.invokeLater(() -> playerStatusLabel.setText("未读取到视频真实时长，播放后会自动校准"));
        warn("未能自动读取该视频时长。播放后如果播放器读取到真实时长，会自动更新房间视频时长。");
        return DEFAULT_VIDEO_DURATION_MS;
    }

    private long detectPlayableDurationMs(Path file, long serverDurationMs) {
        long durationMs = waitForPlayerDurationMs(3000);
        if (durationMs > 0) {
            return durationMs;
        }
        durationMs = VideoMetadataReader.readDurationMs(file);
        if (durationMs > 0) {
            return durationMs;
        }
        return serverDurationMs > 0 ? serverDurationMs : DEFAULT_VIDEO_DURATION_MS;
    }

    private long waitForPlayerDurationMs(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long durationMs = videoPlayer.durationMs();
            if (durationMs > 0) {
                return durationMs;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return videoPlayer.durationMs();
    }

    private long clampSeekPosition(long positionMs, long durationMs) {
        if (durationMs <= 0) {
            return Math.max(0, positionMs);
        }
        long max = Math.max(0, durationMs - SEEK_END_GUARD_MS);
        return Math.max(0, Math.min(positionMs, max));
    }

    private static String sanitizeFileName(String fileName) {
        String cleaned = fileName.replace('\\', '_').replace('/', '_').trim();
        return cleaned.isEmpty() ? "video.dat" : cleaned;
    }

    private Protocol.Response sendRequest(Protocol.Request request) throws Exception {
        if (out == null) {
            throw new IOException("请先连接服务器");
        }
        ResponseWaiter waiter = new ResponseWaiter();
        waiters.put(request.requestId, waiter);
        synchronized (out) {
            out.writeObject(request);
            out.flush();
            out.reset();
        }
        return waiter.await();
    }

    private long nextRequestId() {
        return requestSeq.getAndIncrement();
    }

    private void requireOk(Protocol.Response response) throws Exception {
        if (!response.ok) {
            throw new Exception(response.message);
        }
    }

    private void runAsync(String title, ThrowingRunnable runnable) {
        new Thread(() -> {
            try {
                runnable.run();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> warn(title + "失败：" + ex.getMessage()));
            }
        }, "client-task").start();
    }

    private boolean ensureConnected() {
        if (socket == null || socket.isClosed()) {
            warn("请先连接服务器");
            return false;
        }
        return true;
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        out = null;
    }

    private void info(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE));
    }

    private void warn(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.WARNING_MESSAGE));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class RoomTreeNode {
        final Protocol.RoomSummary room;
        final List<Protocol.VideoInfo> videos;

        RoomTreeNode(Protocol.RoomSummary room, List<Protocol.VideoInfo> videos) {
            this.room = room;
            this.videos = videos;
        }
    }

    private static final class ResponseWaiter {
        private Protocol.Response response;

        synchronized void complete(Protocol.Response response) {
            this.response = response;
            notifyAll();
        }

        synchronized Protocol.Response await() throws InterruptedException {
            while (response == null) {
                wait();
            }
            return response;
        }
    }
}
