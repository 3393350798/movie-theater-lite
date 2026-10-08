# 多人影院系统

这是一个 Java 课程设计项目，主要实现“多人同步观影”的核心流程：

1. 用户注册、登录。
2. 用户创建可长期保留的观影房间。
3. 其他用户通过分享 key 加入房间。
4. 视频上传到当前房间的视频库，服务端按房间保存视频文件。
5. 房主选择房间当前视频，并控制播放、暂停和进度条。
6. 其他成员自动同步播放状态和视频进度。
7. 房间内支持文字聊天、成员列表和基础异常提示。

## 技术结构

- 客户端：Java Swing 图形界面，中间视频区优先使用 VLCJ + VLC 内嵌播放器，JavaFX 作为备用。
- 服务端：Java Socket + 多线程。
- 数据保存：支持 MySQL 数据库和本地文件两种方式。默认 `auto` 模式优先使用 MySQL，连接失败时回退到本地文件，便于课堂演示。
- 视频文件保存：按房间保存到 `server-data/rooms/<房间key>`。

说明：客户端左侧在未进入房间时显示“我的房间”，进入房间后切换为“房间视频库”。房间如不删除会一直保存在服务端数据层中；房间内视频也随房间保存。上传视频时系统优先从 MP4 容器元数据读取真实时长，不需要手动填写视频时长。若播放器后续读到更准确的真实时长，客户端会自动把该时长回写到房间视频记录，进度条会按真实时长显示。

## 编译

双击：

```bat
build.bat
```

或在当前目录执行：

```bat
javac -encoding UTF-8 -d out @sources.txt
```

推荐直接使用 `build.bat`，它会自动收集源码。

## 运行

首次运行可将 `server.properties.example` 复制为 `server.properties`，并填写自己的数据库配置；也可以在服务端启动时按提示配置。实际配置文件不提交到 Git。

使用 MySQL 时，需要将 MySQL Connector/J 9.4.0 驱动放在项目上一级的 `lib/mysql-connector-j-9.4.0.jar`，与启动脚本中的路径保持一致。建表脚本为 `schema.sql`。本地文件存储模式不需要 MySQL 驱动。

1. 启动服务器：

```bat
run_server.bat
```

2. 启动第一个客户端：

```bat
run_client.bat
```

3. 再双击一次 `run_client.bat` 启动第二个客户端。

如果启动服务端时端口被占用，服务端会在控制台自动提示是否结束占用端口的进程。也可以按提示输入新的端口，例如 `5051`，客户端连接时端口也填 `5051`。

服务端启动时会询问数据保存方式：

- 严格 MySQL：必须连接数据库，失败时提示重新填写 `db.url`、`db.user`、`db.password`。
- 兼容模式：优先使用 MySQL，失败时自动回退本地文件。
- 本地文件：只使用 `server-data/store.dat`。

服务端启动后会同时打开“服务端管理”窗口，可查看用户和房间数据，并支持修改用户昵称、重置用户密码。该窗口关闭后只隐藏，不会停止服务端。

## 启用中间视频播放

推荐使用 VLCJ + VLC。`run_client.bat` 会先启动检测器：

- 如果未检测到 VLCJ Java 依赖，会弹窗询问是否自动下载到 `vlcj-lib`，包括 `vlcj`、`vlcj-natives`、`jna`、`jna-platform` 和 `slf4j`。
- 如果未检测到本机 VLC，会提示是否打开 VideoLAN 官方下载页。安装 64 位 VLC 后重新启动客户端即可；若安装目录不是默认路径，再设置 `VLC_HOME` 指向 VLC 安装目录。
- 如果 VLCJ + VLC 可用，客户端中间区域会用 VLC 内嵌播放，支持播放、暂停、进度跳转和当前进度读取。
- 如果两种内嵌播放器都不可用，程序仍可运行，但只能用“下载/播放视频”调用系统播放器；该方式不能可靠同步控制进度。

VLC 官方下载页：

```text
https://www.videolan.org/vlc/download-windows.html
```

VLC 默认安装路径通常是：

```text
C:\Program Files\VideoLAN\VLC
```

注意：当前项目默认使用 64 位 JDK。VLCJ 内嵌播放器要求 Java 和 VLC 位数一致，因此应安装 64 位 VLC。若只检测到 `C:\Program Files (x86)\VideoLAN\VLC`，说明通常是 32 位 VLC，VLC 单独播放视频可以，但不能被 64 位 Java 内嵌调用。

也可以设置环境变量：

```text
VLC_HOME=C:\Program Files\VideoLAN\VLC
```

## 使用步骤

1. 两个客户端都连接 `127.0.0.1:5050`。
2. 在两个客户端分别注册并登录不同用户。
3. 第一个客户端点击“创建房间”，复制生成的分享 key。
4. 第二个客户端输入分享 key，点击“加入房间”。
5. 第一个客户端进入房间后点击“上传视频”，把视频上传到该房间的视频库。
6. 房主在房间视频库中选择视频，点击“设为当前视频”。
7. 房主点击播放、暂停、拖动进度条，第二个客户端会同步播放状态和视频进度。
8. 两个客户端在聊天框发送文字消息，双方都能收到。

补充：双击“我的房间”里的房间可直接进入；进入房间后，左侧列表显示该房间的视频库。房主删除房间后，服务端会删除该房间的视频记录和视频文件。

## 文件说明

- `src/movietheater/common/Protocol.java`：客户端和服务端共用的消息协议、用户、房间、视频、聊天实体。
- `src/movietheater/server/MovieTheaterServer.java`：Socket 多线程服务端。
- `src/movietheater/server/ServerAdminFrame.java`：服务端管理窗口，可查看用户、房间并维护用户昵称和密码。
- `src/movietheater/server/DataStore.java`：数据存储接口和记录类。
- `src/movietheater/server/FileDataStore.java`：本地文件数据存储实现。
- `src/movietheater/server/JdbcDataStore.java`：MySQL 数据库存储实现。
- `src/movietheater/server/PasswordUtil.java`：密码 SHA-256 摘要工具。
- `schema.sql`：MySQL 数据库表结构。
- `src/movietheater/client/MovieTheaterClient.java`：Swing 客户端。
- `src/movietheater/client/MovieTheaterClientLauncher.java`：客户端启动器和依赖检测。
- `src/movietheater/client/VlcjVideoPlayer.java`：VLCJ 内嵌播放器实现。

## 数据说明

系统支持 MySQL 数据库存储，也支持本地文件兼容存储。本地文件模式保存路径为：

```text
server-data/store.dat
```

房间视频文件按房间 key 分目录保存：

```text
server-data/rooms/<房间key>/
```

旧版本的个人视频库模式已经弃用。现在视频必须上传到某个房间的视频库中，房间不删除则房间和视频记录会一直存在。

MySQL 模式下使用 `schema.sql` 中的房间模型表结构：

- `users`：用户注册和登录信息。
- `rooms`：房间基本信息、当前视频和播放状态。
- `room_members`：用户加入过的房间关系。
- `videos`：房间视频库元数据，使用 `room_key` 关联房间。
- `chats`：房间聊天记录。

注意：废弃的是旧版本“按用户保存个人视频库”的表关系，不是废弃数据库。现在视频元数据按房间保存，`videos` 表通过 `room_key` 关联 `rooms`。
