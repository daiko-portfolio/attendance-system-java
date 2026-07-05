package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

    private record AttendanceKey(long personId, String attendanceDate, int checkNo) {}

    private record SlotKey(String attendanceDate, int checkNo) implements Comparable<SlotKey> {
        @Override
        public int compareTo(SlotKey other) {
            int cmp = this.attendanceDate.compareTo(other.attendanceDate);
            if (cmp != 0) return cmp;
            return Integer.compare(this.checkNo, other.checkNo);
        }
    }

    public record Slot(String date, int checkNo, String dateLabel, String checkLabel) {}

    public static class MonthSummary {
        public int totalMax = 0, totalDays = 0, totalRestHours = 0;
        public int academicMax = 0, academicDays = 0, academicRestHours = 0;
        public int practicalMax = 0, practicalDays = 0, practicalRestHours = 0;
    }

    public record Cell(String lessonType, Integer attendedHours) {}

    public record MonthlyRow(
            int attendanceNo,
            String name,
            List<Cell> cells,
            int totalAttended, int totalAbsent, int totalMax, int totalDays, int totalRestHours,
            int academicAttended, int academicAbsent, int academicMax,
            int practicalAttended, int practicalAbsent, int practicalMax
    ) {}

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
                (rs, rowNum) -> new ClassOption(rs.getLong("class_id"), rs.getString("class_name"))
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
                    (rs, rowNum) -> new PersonOption(rs.getLong("person_id"), rs.getInt("attendance_no"), rs.getString("name")),
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
                    (rs, rowNum) -> new AttendanceRecord(
                            rs.getLong("person_id"),
                            rs.getString("attendance_date"),
                            rs.getInt("check_no"),
                            rs.getString("lesson_type"),
                            (Integer) rs.getObject("attended_hours")
                    ),
                    selectedClassId, firstDay.toString(), nextMonthStart.toString()
            );

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
