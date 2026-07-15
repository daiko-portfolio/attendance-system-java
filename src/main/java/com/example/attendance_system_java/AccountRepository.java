package com.example.attendance_system_java;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * ログインアカウントのDBアクセス層。生JDBCで書いている。
 * SQLはすべてここに集約する。
 */
@Repository
public class AccountRepository {

    private final DataSource dataSource;

    public AccountRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Account(
            long accountId,
            String username,
            String passwordHash,
            String role,
            Long teacherId,
            Long personId,
            String displayName,
            boolean isActive
    ) {}

    /**
     * ユーザー名でアカウントを検索する。見つからなければnullを返す。
     * teachers/personsを左外部結合して、教師名・生徒名を表示名として一緒に取得している。
     */
    public Account findByUsername(String username) {
        String sql = """
                SELECT a.account_id, a.username, a.password_hash, a.role,
                       a.teacher_id, a.person_id, a.is_active,
                       t.teacher_name, p.name AS person_name
                FROM accounts a
                LEFT JOIN teachers t ON a.teacher_id = t.teacher_id
                LEFT JOIN persons p ON a.person_id = p.person_id
                WHERE a.username = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapAccount(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Account mapAccount(ResultSet rs) throws SQLException {
        Long teacherId = null;
        Object rawTeacherId = rs.getObject("teacher_id");
        if (rawTeacherId instanceof Number) {
            teacherId = ((Number) rawTeacherId).longValue();
        }

        Long personId = null;
        Object rawPersonId = rs.getObject("person_id");
        if (rawPersonId instanceof Number) {
            personId = ((Number) rawPersonId).longValue();
        }

        String role = rs.getString("role");

        String displayName;
        if (role.equals("TEACHER")) {
            displayName = rs.getString("teacher_name");
        } else if (role.equals("STUDENT")) {
            displayName = rs.getString("person_name");
        } else {
            displayName = "管理者";
        }

        return new Account(
                rs.getLong("account_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                role,
                teacherId,
                personId,
                displayName,
                rs.getInt("is_active") != 0
        );
    }
}
