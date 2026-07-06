package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 部屋（使用場所）マスタのDBアクセス層。
 * 構造は TeacherRepository とほぼ同じで、対象テーブルが rooms になっている。
 */
@Repository
public class RoomRepository {

    private final JdbcTemplate jdbcTemplate;

    public RoomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Room(long roomId, String roomName, boolean isActive) {}

    private static class RoomMapper implements RowMapper<Room> {
        @Override
        public Room mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Room(
                    rs.getLong("room_id"),
                    rs.getString("room_name"),
                    rs.getInt("is_active") != 0
            );
        }
    }

    /** 全部屋を取得する（無効な部屋も含む） */
    public List<Room> findAll() {
        String sql = """
                SELECT room_id, room_name, is_active
                FROM rooms
                ORDER BY is_active DESC, room_id ASC
                """;
        return jdbcTemplate.query(sql, new RoomMapper());
    }

    public void insert(String roomName) {
        jdbcTemplate.update(
                "INSERT INTO rooms (room_name, is_active) VALUES (?, 1)",
                roomName
        );
    }

    public void update(long roomId, String roomName, int isActive) {
        jdbcTemplate.update(
                "UPDATE rooms SET room_name = ?, is_active = ? WHERE room_id = ?",
                roomName, isActive, roomId
        );
    }
}
