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
 * 教師マスタのDBアクセス層。生JDBC（Connection/PreparedStatement/ResultSet）で書いている。
 * （スケジュール表示用の findActiveTeachers は ScheduleRegisterRepository 側にもあるが、
 *   あちらは「有効な教師だけ」を取る参照専用。こちらは管理画面用に
 *   無効な教師も含めた一覧取得・追加・更新を担当する）
 *
 * SQLExceptionはこのRepository内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元（Service/Controller）にthrowsを伝播させない。
 */
@Repository
public class TeacherRepository {

    private final DataSource dataSource;

    public TeacherRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // 管理画面の1行分（無効フラグも持つ）
    public record Teacher(long teacherId, String teacherName, boolean isActive) {}

    /**
     * 全教師を取得する（無効な教師も含む）。
     * 管理画面では、無効にした教師を再度有効に戻せるようにしたいので全件を返す。
     */
    public List<Teacher> findAll() {
        String sql = """
                SELECT teacher_id, teacher_name, is_active
                FROM teachers
                ORDER BY is_active DESC, teacher_id ASC
                """;

        List<Teacher> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long teacherId = rs.getLong("teacher_id");
                String teacherName = rs.getString("teacher_name");
                boolean isActive = rs.getInt("is_active") != 0;
                result.add(new Teacher(teacherId, teacherName, isActive));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    public void insert(String teacherName) {
        String sql = "INSERT INTO teachers (teacher_name, is_active) VALUES (?, 1)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, teacherName);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(long teacherId, String teacherName, int isActive) {
        String sql = "UPDATE teachers SET teacher_name = ?, is_active = ? WHERE teacher_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, teacherName);
            stmt.setInt(2, isActive);
            stmt.setLong(3, teacherId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
