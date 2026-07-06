package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 教師マスタのDBアクセス層。
 * スケジュール機能と同じく、SQLはすべてこのRepositoryに集約する。
 * （スケジュール表示用の findActiveTeachers は ScheduleRepository 側にもあるが、
 *   あちらは「有効な教師だけ」を取る参照専用。こちらは管理画面用に
 *   無効な教師も含めた一覧取得・追加・更新を担当する）
 */
@Repository
public class TeacherRepository {

    private final JdbcTemplate jdbcTemplate;

    public TeacherRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 管理画面の1行分（無効フラグも持つ）
    public record Teacher(long teacherId, String teacherName, boolean isActive) {}

    private static class TeacherMapper implements RowMapper<Teacher> {
        @Override
        public Teacher mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Teacher(
                    rs.getLong("teacher_id"),
                    rs.getString("teacher_name"),
                    rs.getInt("is_active") != 0
            );
        }
    }

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
        return jdbcTemplate.query(sql, new TeacherMapper());
    }

    public void insert(String teacherName) {
        jdbcTemplate.update(
                "INSERT INTO teachers (teacher_name, is_active) VALUES (?, 1)",
                teacherName
        );
    }

    public void update(long teacherId, String teacherName, int isActive) {
        jdbcTemplate.update(
                "UPDATE teachers SET teacher_name = ?, is_active = ? WHERE teacher_id = ?",
                teacherName, isActive, teacherId
        );
    }
}
