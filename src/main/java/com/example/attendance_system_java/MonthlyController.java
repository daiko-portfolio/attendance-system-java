package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 月次出欠表画面のController。全Controllerの中で最も処理が複雑な画面。
 *
 * ■ 全体の考え方
 * DBの attendance テーブルは「生徒×日付×午前午後」で1行、という縦持ちのデータだが、
 * 画面には「生徒を縦、日付×午前午後を横」に並べた表を出したい。
 * そのため、DBから取得した行データを、いったんJavaのMap（辞書）に詰め替えてから、
 * 「生徒でループ」「その中で日付×午前午後でループ」して該当データを探す、という
 * 2段階の組み立てを行っている。
 *
 * ■ AttendanceKey / SlotKey について
 * どちらも複数の値をまとめて1つの「キー」として扱うための入れ物（record）。
 * recordは自動でequals()/hashCode()を実装してくれるため、
 * 同じ値を持つ2つのAttendanceKeyは「同じキー」としてMapやSetで正しく扱われる
 * （もし普通のクラスで作ると、この自動生成が無く別物として扱われてしまう）。
 */
@Controller
public class MonthlyController {

    private final JdbcTemplate jdbcTemplate;

    public MonthlyController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ClassOption(long classId, String className) {}

    public record PersonOption(long personId, int attendanceNo, String name) {}

    public record AttendanceRecord(
            long personId,
            String attendanceDate,
            int checkNo,
            String lessonType,
            Integer attendedHours
    ) {}

    // 「この生徒の、この日付・この午前午後のデータ」を一意に特定するためのキー
    private record AttendanceKey(long personId, String attendanceDate, int checkNo) {}

    // 「この日付・この午前午後」という、表の列（1コマ分）を表すキー。
    // Comparable<SlotKey>を実装しているのは、この後 TreeSet<SlotKey> に入れて
    // 自動的に日付順・午前午後順に並び替えさせるため
    // （TreeSetは追加した要素を、compareTo()の結果に従って常に並び替えて保持してくれる）
    private record SlotKey(String attendanceDate, int checkNo) implements Comparable<SlotKey> {
        @Override
        public int compareTo(SlotKey other) {
            int cmp = this.attendanceDate.compareTo(other.attendanceDate);
            if (cmp != 0) return cmp;
            return Integer.compare(this.checkNo, other.checkNo);
        }
    }

    // 表の列見出し（例：「7/6」「AM」）を表示用に持つ入れ物
    public record Slot(String date, int checkNo, String dateLabel, String checkLabel) {}

    // 月全体の集計値。合計を少しずつ足していく必要があるため、
    // recordではなく、後から書き換え可能な普通のクラス（フィールドも public のまま）にしている
    public static class MonthSummary {
        public int totalMax = 0, totalDays = 0, totalRestHours = 0;
        public int academicMax = 0, academicDays = 0, academicRestHours = 0;
        public int practicalMax = 0, practicalDays = 0, practicalRestHours = 0;
    }

    // 表の1マス分（ある生徒の、ある日付・午前午後のセル）の表示内容
    public record Cell(String lessonType, Integer attendedHours) {}

    public record MonthlyRow(
            int attendanceNo,
            String name,
            List<Cell> cells,
            int totalAttended, int totalAbsent, int totalMax, int totalDays, int totalRestHours,
            int academicAttended, int academicAbsent, int academicMax,
            int practicalAttended, int practicalAbsent, int practicalMax
    ) {}

    /**
     * RowMapperは名前付きクラスで定義する（ラムダ式は使わない）。
     * SQLExceptionはここでcatchしてRuntimeExceptionに変換し、throwsを外へ伝えない。
     */
    private static class ClassOptionMapper implements RowMapper<ClassOption> {
        @Override
        public ClassOption mapRow(ResultSet rs, int rowNum) {
            try {
                return new ClassOption(rs.getLong("class_id"), rs.getString("class_name"));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class PersonOptionMapper implements RowMapper<PersonOption> {
        @Override
        public PersonOption mapRow(ResultSet rs, int rowNum) {
            try {
                return new PersonOption(rs.getLong("person_id"), rs.getInt("attendance_no"), rs.getString("name"));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class AttendanceRecordMapper implements RowMapper<AttendanceRecord> {
        @Override
        public AttendanceRecord mapRow(ResultSet rs, int rowNum) {
            try {
                return new AttendanceRecord(
                        rs.getLong("person_id"),
                        rs.getString("attendance_date"),
                        rs.getInt("check_no"),
                        rs.getString("lesson_type"),
                        (Integer) rs.getObject("attended_hours")
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 授業時間を「6時間=1日」換算した日数と余り時間に変換する（例：15h → 2日3h）。
     * 戻り値は長さ2の配列 [日数, 余り時間] としている
     * （Javaのメソッドは戻り値を1つしか返せないため、複数の値をまとめて返したい時に
     *  こうした配列やrecordがよく使われる）。
     */
    private static int[] convertHoursToDays(int hours) {
        return new int[] { hours / 6, hours % 6 };
    }

    @GetMapping("/monthly")
    public String monthly(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "month", required = false) String selectedMonth,
            Model model
    ) {
        if (selectedMonth == null || selectedMonth.isBlank()) {
            selectedMonth = YearMonth.now().toString();
        }

        YearMonth yearMonth = YearMonth.parse(selectedMonth);
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate nextMonthStart = yearMonth.plusMonths(1).atDay(1);

        List<ClassOption> classes = jdbcTemplate.query(
                """
                SELECT class_id, class_name
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """,
                new ClassOptionMapper()
        );

        List<Slot> slots = new ArrayList<>();
        List<MonthlyRow> monthlyRows = new ArrayList<>();
        MonthSummary monthSummary = new MonthSummary();

        if (selectedClassId != null && !selectedClassId.isBlank()) {

            List<PersonOption> persons = jdbcTemplate.query(
                    """
                    SELECT person_id, attendance_no, name
                    FROM persons
                    WHERE class_id = ?
                    AND is_active = 1
                    ORDER BY attendance_no
                    """,
                    new PersonOptionMapper(),
                    selectedClassId
            );

            List<AttendanceRecord> records = jdbcTemplate.query(
                    """
                    SELECT
                        p.person_id,
                        a.attendance_date,
                        a.check_no,
                        a.lesson_type,
                        a.attended_hours
                    FROM attendance a
                    JOIN persons p ON a.person_id = p.person_id
                    WHERE p.class_id = ?
                    AND a.attendance_date >= ?
                    AND a.attendance_date < ?
                    ORDER BY a.attendance_date, a.check_no, p.attendance_no
                    """,
                    new AttendanceRecordMapper(),
                    selectedClassId, firstDay.toString(), nextMonthStart.toString()
            );

            // DBから取ってきた「1行=1生徒×1日×午前午後」のrecordsを、
            // 探しやすい形（Map）に詰め替える。
            //   attendanceMap : 「この生徒のこのコマ」→ そのデータ、を高速に引くため
            //   slotSet       : 表の列を「日付の重複なし・順序どおり」に集めるため（TreeSetなので自動で整列される）
            //   slotTypeMap   : 各コマの授業属性（学科/実技）を1回引くだけで済むように
            Map<AttendanceKey, AttendanceRecord> attendanceMap = new HashMap<>();
            TreeSet<SlotKey> slotSet = new TreeSet<>();
            Map<SlotKey, String> slotTypeMap = new HashMap<>();

            for (AttendanceRecord record : records) {
                AttendanceKey key = new AttendanceKey(record.personId(), record.attendanceDate(), record.checkNo());
                attendanceMap.put(key, record);

                SlotKey slotKey = new SlotKey(record.attendanceDate(), record.checkNo());
                slotSet.add(slotKey);
                slotTypeMap.put(slotKey, record.lessonType());
            }

            // slotSetの中身（TreeSetなので日付・午前午後順に並んでいる）から、
            // 表の列見出し用データ（Slot）を組み立てる
            for (SlotKey slotKey : slotSet) {
                String dateLabel = Integer.parseInt(slotKey.attendanceDate().substring(5, 7))
                        + "/" + Integer.parseInt(slotKey.attendanceDate().substring(8, 10));

                String checkLabel = switch (slotKey.checkNo()) {
                    case 1 -> "AM";
                    case 2 -> "PM";
                    default -> String.valueOf(slotKey.checkNo());
                };

                slots.add(new Slot(slotKey.attendanceDate(), slotKey.checkNo(), dateLabel, checkLabel));
            }

            // 月全体の「最大授業時間」を集計する。
            // ※全生徒の出席合計ではなく、「その月に登録されているコマの数」から
            //  最大値（1コマ=3h）を出しているだけの点に注意
            //  （欠席者がいても、コマ自体があれば3hとしてカウントされる）
            for (Slot slot : slots) {
                String lessonType = slotTypeMap.get(new SlotKey(slot.date(), slot.checkNo()));

                monthSummary.totalMax += 3;

                if ("学科".equals(lessonType)) {
                    monthSummary.academicMax += 3;
                } else if ("実技".equals(lessonType)) {
                    monthSummary.practicalMax += 3;
                }
            }

            int[] totalDaysHours = convertHoursToDays(monthSummary.totalMax);
            monthSummary.totalDays = totalDaysHours[0];
            monthSummary.totalRestHours = totalDaysHours[1];

            int[] academicDaysHours = convertHoursToDays(monthSummary.academicMax);
            monthSummary.academicDays = academicDaysHours[0];
            monthSummary.academicRestHours = academicDaysHours[1];

            int[] practicalDaysHours = convertHoursToDays(monthSummary.practicalMax);
            monthSummary.practicalDays = practicalDaysHours[0];
            monthSummary.practicalRestHours = practicalDaysHours[1];

            for (PersonOption person : persons) {

                List<Cell> cells = new ArrayList<>();

                int totalAttended = 0, totalMax = 0;
                int academicAttended = 0, academicMax = 0;
                int practicalAttended = 0, practicalMax = 0;

                for (Slot slot : slots) {
                    AttendanceRecord data = attendanceMap.get(
                            new AttendanceKey(person.personId(), slot.date(), slot.checkNo())
                    );

                    String displayType;
                    Integer displayHours;

                    if (data != null) {
                        displayType = data.lessonType();
                        displayHours = data.attendedHours();

                        totalAttended += displayHours;
                        totalMax += 3;

                        if ("学科".equals(data.lessonType())) {
                            academicAttended += displayHours;
                            academicMax += 3;
                        } else if ("実技".equals(data.lessonType())) {
                            practicalAttended += displayHours;
                            practicalMax += 3;
                        }
                    } else {
                        displayType = "未登録";
                        displayHours = null;
                    }

                    cells.add(new Cell(displayType, displayHours));
                }

                int totalAbsent = totalMax - totalAttended;
                int academicAbsent = academicMax - academicAttended;
                int practicalAbsent = practicalMax - practicalAttended;

                int[] totalDaysRest = convertHoursToDays(totalAttended);

                monthlyRows.add(new MonthlyRow(
                        person.attendanceNo(),
                        person.name(),
                        cells,
                        totalAttended, totalAbsent, totalMax, totalDaysRest[0], totalDaysRest[1],
                        academicAttended, academicAbsent, academicMax,
                        practicalAttended, practicalAbsent, practicalMax
                ));
            }
        }

        model.addAttribute("classes", classes);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("selectedMonth", selectedMonth);
        model.addAttribute("slots", slots);
        model.addAttribute("monthlyRows", monthlyRows);
        model.addAttribute("monthSummary", monthSummary);

        return "monthly";
    }
}
