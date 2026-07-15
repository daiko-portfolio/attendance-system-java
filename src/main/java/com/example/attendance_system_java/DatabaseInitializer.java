package com.example.attendance_system_java;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 起動時にテーブルが無ければ作る処理。
 * Flask版 app.py の init_db() に相当する。
 * CREATE TABLE IF NOT EXISTS なので既にテーブルがあれば何もしない。
 *
 * ■ Spring Boot初心者向けメモ
 * ・@Component を付けたクラスは、アプリ起動時にSpringが自動でインスタンス化して管理してくれる
 *   （これを「Beanとして登録する」と言う）。Flaskのように自分で new して使うのではなく、
 *   Spring側が必要なタイミングで勝手に作ってくれるイメージ。
 * ・CommandLineRunner は「アプリが起動しきった直後に1回だけ実行してほしい処理」を
 *   表すためのSpring標準インターフェース。これを implements して run() の中身を書くと、
 *   Spring Bootが起動完了時に自動でrun()を呼び出してくれる。
 *   （app.pyの `if __name__ == "__main__": init_db()` の部分に相当）
 * ・コンストラクタで JdbcTemplate を受け取っているのは「コンストラクタインジェクション」という
 *   Springの標準的な書き方。JdbcTemplate自体もSpringが自動生成したBeanで、
 *   Springがこのクラスを作るときに自動的に渡してくれる（自分でnewしなくてよい）。
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    // Spring Bootの起動が完了すると、このrun()メソッドが自動的に1回だけ呼ばれる
    @Override
    public void run(String... args) {

        // 教師マスタ
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teachers (
                    teacher_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    teacher_name TEXT NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
                """);

        // 場所（物理教室）マスタ
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rooms (
                    room_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    room_name TEXT NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
                """);

        // 授業スケジュール
        // UNIQUE制約3つで排他制御を行う
        //   1. 同じクラスの同じ日・同じ区分に授業は1つだけ
        //   2. 同じ教師は同じ日・同じ区分に1か所だけ
        //   3. 同じ場所は同じ日・同じ区分に1クラスだけ
        // SQLiteのUNIQUEはNULL同士を別物として扱うため、
        // 休みの行（teacher_id/room_idがNULL）は何行あっても制約に掛からない
        // check_noの3は「放課後」用（UIは未対応。将来の機能追加に備えてDB側だけ許可している）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schedules (
                    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_id INTEGER NOT NULL,
                    schedule_date TEXT NOT NULL,
                    check_no INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT '通常',
                    lesson_type TEXT,
                    teacher_id INTEGER,
                    room_id INTEGER,
                    memo TEXT,
                    UNIQUE(class_id, schedule_date, check_no),
                    UNIQUE(teacher_id, schedule_date, check_no),
                    UNIQUE(room_id, schedule_date, check_no),
                    CHECK(check_no IN (1, 2, 3)),
                    CHECK(status IN ('通常', '休み')),
                    CHECK(lesson_type IN ('学科', '実技') OR lesson_type IS NULL),
                    FOREIGN KEY(class_id) REFERENCES classes(class_id),
                    FOREIGN KEY(teacher_id) REFERENCES teachers(teacher_id),
                    FOREIGN KEY(room_id) REFERENCES rooms(room_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_schedules_date_check
                ON schedules(schedule_date, check_no)
                """);

        // ログインアカウント
        // role='TEACHER'ならteacher_id、role='STUDENT'ならperson_idだけを使う
        // （ADMINはどちらもNULL）。1つのテーブルにまとめて、ログイン時は
        // ユーザー名から検索してroleを見るだけで教師/生徒/管理者を自動判別できるようにしている。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    account_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL,
                    teacher_id INTEGER,
                    person_id INTEGER,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    CHECK(role IN ('STUDENT', 'TEACHER', 'ADMIN')),
                    FOREIGN KEY(teacher_id) REFERENCES teachers(teacher_id),
                    FOREIGN KEY(person_id) REFERENCES persons(person_id)
                )
                """);

        // classes に default_room_id 列が無ければ追加する
        // SQLiteのALTER TABLEはADD COLUMNをサポートしている
        // （古いDBファイルに対して、後から列を1つ増やす操作。既存データは消えない）
        if (!columnExists("classes", "default_room_id")) {
            jdbcTemplate.execute("ALTER TABLE classes ADD COLUMN default_room_id INTEGER");
        }

        // 既にschedulesが作られていてmemo列が無い場合に備えて追加する
        if (!columnExists("schedules", "memo")) {
            jdbcTemplate.execute("ALTER TABLE schedules ADD COLUMN memo TEXT");
        }

        // 教師・場所マスタの編集画面はまだ無いため、
        // テーブルが空の時だけ初期データを入れておく
        Integer teacherCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teachers", Integer.class);
        if (teacherCount != null && teacherCount == 0) {
            jdbcTemplate.update("INSERT INTO teachers (teacher_name) VALUES (?)", "田中");
            jdbcTemplate.update("INSERT INTO teachers (teacher_name) VALUES (?)", "田代");
            jdbcTemplate.update("INSERT INTO teachers (teacher_name) VALUES (?)", "多田");
        }

        Integer roomCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rooms", Integer.class);
        if (roomCount != null && roomCount == 0) {
            jdbcTemplate.update("INSERT INTO rooms (room_name) VALUES (?)", "2F教室");
            jdbcTemplate.update("INSERT INTO rooms (room_name) VALUES (?)", "1F教室");
            jdbcTemplate.update("INSERT INTO rooms (room_name) VALUES (?)", "外部A教室");
        }

        // ログイン画面が無いとアプリに入れなくなってしまうため、デモ用の3アカウント
        // （管理者・教師・生徒）を用意しておく。ポートフォリオのデモ用パスワードなので、
        // 「無い時だけ作る」ではなく起動のたびに固定パスワードで上書き（UPSERT）している
        // （こうしておかないと、後でデモ用パスワードを変更した時に古いDBファイルに
        // 　残った古いパスワードのままになってしまうため）。
        // teacher_id=1 / person_id=1 は、上の初期データやサンプルデータ投入で
        // 実際に作られる1件目の教師・生徒（田中／吉田）を指すようにしている。
        String adminHash = passwordEncoder.encode("AdminDemo2026!");
        String teacherHash = passwordEncoder.encode("TeacherDemo2026!");
        String studentHash = passwordEncoder.encode("StudentDemo2026!");

        jdbcTemplate.update("""
                INSERT INTO accounts (username, password_hash, role)
                VALUES (?, ?, 'ADMIN')
                ON CONFLICT (username) DO UPDATE SET password_hash = excluded.password_hash
                """, "admin", adminHash);
        jdbcTemplate.update("""
                INSERT INTO accounts (username, password_hash, role, teacher_id)
                VALUES (?, ?, 'TEACHER', 1)
                ON CONFLICT (username) DO UPDATE SET password_hash = excluded.password_hash
                """, "teacher1", teacherHash);
        jdbcTemplate.update("""
                INSERT INTO accounts (username, password_hash, role, person_id)
                VALUES (?, ?, 'STUDENT', 1)
                ON CONFLICT (username) DO UPDATE SET password_hash = excluded.password_hash
                """, "student1", studentHash);
    }

    /**
     * 指定テーブルに指定列が存在するかをPRAGMAで確認する。
     * PRAGMA table_info(テーブル名) はSQLite独自のSQLで、
     * そのテーブルの列一覧（列名・型など）を返してくれる。
     * jdbcTemplate.queryForList(...) は、結果の各行を
     * 「列名 -> 値」のMapとして受け取れる、JdbcTemplateの汎用メソッド。
     * RowMapperを自分で書くまでもない単純な取得の時によく使う。
     */
    private boolean columnExists(String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");

        for (Map<String, Object> column : columns) {
            Object name = column.get("name");
            if (name != null && name.toString().equals(columnName)) {
                return true;
            }
        }
        return false;
    }
}
