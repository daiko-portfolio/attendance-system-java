package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
 *
 * DBアクセスは生JDBC（Connection/PreparedStatement/ResultSet）で書いている。
 * SQLExceptionはこのクラス内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元にthrowsを伝播させない。
 */
@Controller
public class AttendanceMonthlyController {

    private final DataSource dataSource;

    public AttendanceMonthlyController(DataSource dataSource) {
        this.dataSource = dataSource;
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
    // 元のSQL（ORDER BY attendance_date, check_no）が既に日付・午前午後順で
    // 返してくれるので、ここでは並び替えは行わず、そのままの順序で1回ずつ集める。
    private record SlotKey(String attendanceDate, int checkNo) {}

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
    public record Cell(String lessonType, Integer attendedHours) {

        /**
         * セルの背景色を決めるCSSクラス名を返す。
         * 三項演算子は使わず、Javaの規約どおりif-elseで書く
         * （Thymeleaf側にこの判定を書くと三項演算子の入れ子になって読みにくいため、
         *  ここで文字列を組み立てて、テンプレート側は参照するだけにしている）。
         */
        public String statusClass() {
            if (attendedHours == null) {
                return "status-none";
            }
            if (attendedHours == 3) {
                return "status-present";
            }
            if (attendedHours == 0) {
                return "status-absent";
            }
            return "status-warning";
        }
    }

    public record MonthlyRow(
            int attendanceNo,
            String name,
            List<Cell> cells,
            int totalAttended, int totalAbsent, int totalMax, int totalDays, int totalRestHours,
            int academicAttended, int academicAbsent, int academicMax,
            int practicalAttended, int practicalAbsent, int practicalMax
    ) {}

    // convertHoursToDays()の戻り値。日数と余り時間の2つをまとめて返すための入れ物
    // （Javaのメソッドは戻り値を1つしか返せないため、複数の値をまとめて返したい時に
    //  こうしたrecordがよく使われる。配列 [日数, 余り時間] より、名前が付いている分読みやすい）
    private record DaysHours(int days, int restHours) {}

    /**
     * 授業時間を「6時間=1日」換算した日数と余り時間に変換する（例：15h → 2日3h）。
     */
    private static DaysHours convertHoursToDays(int hours) {
        return new DaysHours(hours / 6, hours % 6);
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

        List<ClassOption> classes = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                     SELECT class_id, class_name
                     FROM classes
                     WHERE is_active = 1
                     ORDER BY class_id
                     """);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                classes.add(new ClassOption(rs.getLong("class_id"), rs.getString("class_name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        List<Slot> slots = new ArrayList<>();
        List<MonthlyRow> monthlyRows = new ArrayList<>();
        MonthSummary monthSummary = new MonthSummary();

        if (selectedClassId != null && !selectedClassId.isBlank()) {

            List<PersonOption> persons = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                         SELECT person_id, attendance_no, name
                         FROM persons
                         WHERE class_id = ?
                         AND is_active = 1
                         ORDER BY attendance_no
                         """)) {

                stmt.setString(1, selectedClassId);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        persons.add(new PersonOption(
                                rs.getLong("person_id"),
                                rs.getInt("attendance_no"),
                                rs.getString("name")
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            List<AttendanceRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
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
                         """)) {

                stmt.setString(1, selectedClassId);
                stmt.setString(2, firstDay.toString());
                stmt.setString(3, nextMonthStart.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // attended_hoursはNULLの可能性があるため、getInt()（NULLだと0になる）ではなく
                        // getObject()で受けてから型を確認して変換する
                        Integer attendedHours = null;
                        Object rawHours = rs.getObject("attended_hours");
                        if (rawHours instanceof Number) {
                            attendedHours = ((Number) rawHours).intValue();
                        }

                        records.add(new AttendanceRecord(
                                rs.getLong("person_id"),
                                rs.getString("attendance_date"),
                                rs.getInt("check_no"),
                                rs.getString("lesson_type"),
                                attendedHours
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            // DBから取ってきた「1行=1生徒×1日×午前午後」のrecordsを、
            // 探しやすい形（Map）に詰め替える。
            //   attendanceMap : 「この生徒のこのコマ」→ そのデータ、を高速に引くため
            //   slotSet       : 表の列を「日付の重複なし・順序どおり」に集めるため
            //                   （SQLが既に日付・午前午後順で返してくれているので、
            //                    LinkedHashSetで登場順のまま重複だけ除けばよい）
            //   slotTypeMap   : 各コマの授業属性（学科/実技）を1回引くだけで済むように
            Map<AttendanceKey, AttendanceRecord> attendanceMap = new HashMap<>();
            LinkedHashSet<SlotKey> slotSet = new LinkedHashSet<>();
            Map<SlotKey, String> slotTypeMap = new HashMap<>();

            for (AttendanceRecord record : records) {
                AttendanceKey key = new AttendanceKey(record.personId(), record.attendanceDate(), record.checkNo());
                attendanceMap.put(key, record);

                SlotKey slotKey = new SlotKey(record.attendanceDate(), record.checkNo());
                slotSet.add(slotKey);
                slotTypeMap.put(slotKey, record.lessonType());
            }

            // slotSetの中身（登場した順＝日付・午前午後順に並んでいる）から、
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

            DaysHours totalDaysHours = convertHoursToDays(monthSummary.totalMax);
            monthSummary.totalDays = totalDaysHours.days();
            monthSummary.totalRestHours = totalDaysHours.restHours();

            DaysHours academicDaysHours = convertHoursToDays(monthSummary.academicMax);
            monthSummary.academicDays = academicDaysHours.days();
            monthSummary.academicRestHours = academicDaysHours.restHours();

            DaysHours practicalDaysHours = convertHoursToDays(monthSummary.practicalMax);
            monthSummary.practicalDays = practicalDaysHours.days();
            monthSummary.practicalRestHours = practicalDaysHours.restHours();

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

                DaysHours totalDaysRest = convertHoursToDays(totalAttended);

                monthlyRows.add(new MonthlyRow(
                        person.attendanceNo(),
                        person.name(),
                        cells,
                        totalAttended, totalAbsent, totalMax, totalDaysRest.days(), totalDaysRest.restHours(),
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
