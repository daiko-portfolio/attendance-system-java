package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.util.List;

/**
 * スケジュール月次カレンダー画面（教室ベース）のController。
 *
 * ・プルダウン用の教室一覧は単純な取得なので、Controllerから直接Repositoryを呼ぶ。
 * ・カレンダーの升目の組み立ては複雑なので、ScheduleCalendarServiceに任せる。
 */
@Controller
public class ScheduleCalendarController {

    private final ScheduleCalendarRepository scheduleCalendarRepository;
    private final ScheduleCalendarService scheduleCalendarService;

    public ScheduleCalendarController(
            ScheduleCalendarRepository scheduleCalendarRepository,
            ScheduleCalendarService scheduleCalendarService
    ) {
        this.scheduleCalendarRepository = scheduleCalendarRepository;
        this.scheduleCalendarService = scheduleCalendarService;
    }

    @GetMapping("/schedule/monthly")
    public String scheduleMonthly(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "month", required = false) String selectedMonth,
            Model model
    ) {
        // month が未指定なら今月を表示する
        YearMonth yearMonth;
        if (selectedMonth == null || selectedMonth.isBlank()) {
            yearMonth = YearMonth.now();
        } else {
            yearMonth = YearMonth.parse(selectedMonth);
        }

        List<ScheduleCalendarRepository.ClassOption> classes = scheduleCalendarRepository.findActiveClasses();

        String className = null;
        List<List<ScheduleCalendarService.DayCell>> weeks = null;

        // 教室が選ばれている時だけカレンダーを組み立てる
        if (selectedClassId != null && !selectedClassId.isBlank()) {

            for (int i = 0; i < classes.size(); i++) {
                ScheduleCalendarRepository.ClassOption classOption = classes.get(i);
                if (String.valueOf(classOption.classId()).equals(selectedClassId)) {
                    className = classOption.className();
                    break;
                }
            }

            weeks = scheduleCalendarService.buildClassCalendar(selectedClassId, yearMonth);
        }

        model.addAttribute("classes", classes);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("selectedMonth", yearMonth.toString());
        model.addAttribute("className", className);
        model.addAttribute("weeks", weeks);

        return "schedule_monthly";
    }

    @GetMapping("/schedule/monthly/teacher")
    public String scheduleMonthlyByTeacher(
            @RequestParam(name = "teacher_id", required = false) String selectedTeacherId,
            @RequestParam(name = "month", required = false) String selectedMonth,
            Model model
    ) {
        YearMonth yearMonth;
        if (selectedMonth == null || selectedMonth.isBlank()) {
            yearMonth = YearMonth.now();
        } else {
            yearMonth = YearMonth.parse(selectedMonth);
        }

        List<ScheduleCalendarRepository.TeacherOption> teachers = scheduleCalendarRepository.findActiveTeachers();

        String teacherName = null;
        List<List<ScheduleCalendarService.DayCell>> weeks = null;

        if (selectedTeacherId != null && !selectedTeacherId.isBlank()) {

            for (int i = 0; i < teachers.size(); i++) {
                ScheduleCalendarRepository.TeacherOption teacherOption = teachers.get(i);
                if (String.valueOf(teacherOption.teacherId()).equals(selectedTeacherId)) {
                    teacherName = teacherOption.teacherName();
                    break;
                }
            }

            weeks = scheduleCalendarService.buildTeacherCalendar(selectedTeacherId, yearMonth);
        }

        model.addAttribute("teachers", teachers);
        model.addAttribute("selectedTeacherId", selectedTeacherId);
        model.addAttribute("selectedMonth", yearMonth.toString());
        model.addAttribute("teacherName", teacherName);
        model.addAttribute("weeks", weeks);

        return "schedule_monthly_teacher";
    }
}
