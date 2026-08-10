package com.example.attendance_system_java;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 部屋（使用場所）マスタのDBアクセス層。生JDBCで書いている。
 * 構造は TeacherMasterRepository とほぼ同じで、対象テーブルが rooms になっている。
 */
@Repository
public class RoomMasterRepository {

    private final DataSource dataSource;

    public RoomMasterRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Room(long roomId, String roomName, boolean isActive) {}

    /** 全部屋を取得する（無効な部屋も含む） */
    public List<Room> findAll() {
        String sql = """
                SELECT room_id, room_name, is_active
                FROM rooms
                ORDER BY is_active DESC, room_id ASC
                """;

        List<Room> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long roomId = rs.getLong("room_id");
                String roomName = rs.getString("room_name");
                boolean isActive = rs.getInt("is_active") != 0;
                result.add(new Room(roomId, roomName, isActive));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    public void insert(String roomName) {
        String sql = "INSERT INTO rooms (room_name, is_active) VALUES (?, 1)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, roomName);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(long roomId, String roomName, int isActive) {
        String sql = "UPDATE rooms SET room_name = ?, is_active = ? WHERE room_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, roomName);
            stmt.setInt(2, isActive);
            stmt.setLong(3, roomId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
