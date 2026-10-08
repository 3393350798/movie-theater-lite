多人影院系统提交包

一、目录说明
1. jar/movie-theater-system.jar
   包含本项目全部 .class 文件，主类为 movietheater.client.MovieTheaterClientLauncher。

2. src/
   项目全部 .java 源代码。

3. database/数据库表.sql
   MySQL 数据库建库建表脚本，是完整可导入的数据库表文件。

4. database/tables/
   按表拆分的 SQL 文件，便于直接查看每张数据库表结构：
   users.sql、rooms.sql、room_members.sql、videos.sql、chats.sql。

5. lib/mysql-connector-j-9.4.0.jar
   MySQL JDBC 驱动，用于服务端严格 MySQL 模式连接数据库。

6. server.properties.example
   服务端配置文件示例。

二、运行方式
1. 启动客户端：
   java -jar "jar/movie-theater-system.jar"

2. 启动服务端：
   java -cp "jar/movie-theater-system.jar;lib/mysql-connector-j-9.4.0.jar" movietheater.server.MovieTheaterServer

3. 使用 MySQL：
   先在 MySQL 中执行 database/数据库表.sql，再根据 server.properties.example 配置 server.properties。

三、说明
本压缩包不包含运行期间产生的缓存、上传视频和本地数据文件。
