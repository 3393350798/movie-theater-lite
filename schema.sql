CREATE DATABASE IF NOT EXISTS movie_theater
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE movie_theater;

CREATE TABLE IF NOT EXISTS users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  nickname VARCHAR(50) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rooms (
  room_key VARCHAR(16) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  host_id INT NOT NULL,
  host_name VARCHAR(50) NOT NULL,
  max_members INT NOT NULL,
  current_video_id INT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  playing BOOLEAN NOT NULL DEFAULT FALSE,
  position_ms BIGINT NOT NULL DEFAULT 0,
  last_updated_at BIGINT NOT NULL,
  INDEX idx_rooms_host(host_id),
  CONSTRAINT fk_rooms_host FOREIGN KEY(host_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_members (
  room_key VARCHAR(16) NOT NULL,
  user_id INT NOT NULL,
  joined_at BIGINT NOT NULL,
  PRIMARY KEY(room_key, user_id),
  CONSTRAINT fk_room_members_room FOREIGN KEY(room_key) REFERENCES rooms(room_key) ON DELETE CASCADE,
  CONSTRAINT fk_room_members_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS videos (
  id INT PRIMARY KEY AUTO_INCREMENT,
  room_key VARCHAR(16) NOT NULL,
  uploader_id INT NOT NULL,
  uploader_name VARCHAR(50) NOT NULL,
  name VARCHAR(255) NOT NULL,
  size BIGINT NOT NULL,
  duration_ms BIGINT NOT NULL,
  uploaded_at BIGINT NOT NULL,
  server_path VARCHAR(500) NOT NULL,
  INDEX idx_videos_room(room_key),
  CONSTRAINT fk_videos_room FOREIGN KEY(room_key) REFERENCES rooms(room_key) ON DELETE CASCADE,
  CONSTRAINT fk_videos_uploader FOREIGN KEY(uploader_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chats (
  id INT PRIMARY KEY AUTO_INCREMENT,
  room_key VARCHAR(16) NOT NULL,
  user_id INT NOT NULL,
  nickname VARCHAR(50) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  created_at BIGINT NOT NULL,
  INDEX idx_chats_room(room_key),
  CONSTRAINT fk_chats_room FOREIGN KEY(room_key) REFERENCES rooms(room_key) ON DELETE CASCADE,
  CONSTRAINT fk_chats_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
