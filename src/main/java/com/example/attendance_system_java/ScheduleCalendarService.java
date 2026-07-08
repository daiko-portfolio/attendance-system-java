package com.example.attendance_system_java;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * スケジュール月次カレンダーの「升目を組み立てる」業務ロジック層。
 *
 * DBから取ってきたスケジュール一覧（日付がバラバラの平らなリスト）を、
 * 紙のカレンダーのような「週×曜日」のマス目に並べ替えるのがこのServiceの役目。
 * 教室ベース・教師ベースのどちらの画面からも同じ組み立てロジックを使えるように、
 * カレンダー組み立て部分（buildWeeks）を共通化してある。
 */
@Service
public class ScheduleCalendarService {

    private final ScheduleCalendarRepository scheduleCalendarRepository;

    public ScheduleCalendarService(ScheduleCalendarRepository scheduleCalendarRepository) {
        this.scheduleCalendarRepository = scheduleCalendarRepository;
    }

    /**
     * 1マスの午前 or 午後の中身。
     * registered=false … その日その区分にスケジュールが無い（空欄表示）
     * holiday=true      … 「休み」として登録されている
     * それ以外          … 教師・場所・属性・メモを表示する
     */
    public record CellSlot(
            boolean registered,
            boolean holiday,
            String lessonType,
            String className,
            String teacherName,
            String roomName,
            String memo
    ) {}

    /**
     * カレンダーの1日分のマス。
     * inMonth=false は「前後の月のはみ出し日」で、薄く表示する用のフラグ。
     */
    public record DayCell(
            String dateStr,
            int dayOfMonth,
            boolean inMonth,
            boolean weekend,
            boolean today,
            CellSlot am,
            CellSlot pm
    ) {}

    /**
     * 教室（コース）ベースのカレンダーを組み立てて返す。
     * 戻り値は「週のリスト」で、各週は「7個のDayCell（月〜日）のリスト」になっている。
     */
    public List<List<DayCell>> buildClassCalendar(String classId, YearMonth yearMonth) {

        // その月を含むカレンダーの表示範囲（月曜始まり）を計算する
        LocalDate gridStart = calcGridStart(yearMonth);
        LocalDate gridEnd = calcGridEnd(yearMonth);

        // 表示範囲のスケジュールをまとめて取得する
        List<ScheduleCalendarRepository.ScheduleSlot> slots =
                scheduleCalendarRepository.findClassSchedulesInRange(
                        classId, gridStart.toString(), gridEnd.toString());

        return buildWeeks(yearMonth, gridStart, gridEnd, slots);
    }

    /**
     * 教師ベースのカレンダーを組み立てて返す。
     * buildClassCalendarとほぼ同じ流れで、取得元のRepositoryメソッドが違うだけ。
     * 升目の組み立て（buildWeeks）は共通のものをそのまま使い回している。
     */
    public List<List<DayCell>> buildTeacherCalendar(String teacherId, YearMonth yearMonth) {

        LocalDate gridStart = calcGridStart(yearMonth);
        LocalDate gridEnd = calcGridEnd(yearMonth);

        List<ScheduleCalendarRepository.ScheduleSlot> slots =
                scheduleCalendarRepository.findTeacherSchedulesInRange(
                        teacherId, gridStart.toString(), gridEnd.toString());

        return buildWeeks(yearMonth, gridStart, gridEnd, slots);
    }

    /**
     * その月の1日を含む週の月曜日（カレンダーの左上）を求める
     */
    private LocalDate calcGridStart(YearMonth yearMonth) {
        LocalDate firstOfMonth = yearMonth.atDay(1);
        // getDayOfWeek().getValue() は 月曜=1 ... 日曜=7。
        // その分だけ戻れば、その週の月曜日になる。
        int dayOfWeekValue = firstOfMonth.getDayOfWeek().getValue();
        return firstOfMonth.minusDays(dayOfWeekValue - 1);
    }

    /**
     * その月の末日を含む週の日曜日（カレンダーの右下）を求める
     */
    private LocalDate calcGridEnd(YearMonth yearMonth) {
        LocalDate lastOfMonth = yearMonth.atEndOfMonth();
        int dayOfWeekValue = lastOfMonth.getDayOfWeek().getValue();
        return lastOfMonth.plusDays(7 - dayOfWeekValue);
    }

    /**
     * カレンダーの升目（週×曜日）を実際に組み立てる共通処理。
     * 教室ベース・教師ベースのどちらのslotリストを渡してもここで同じように組み立てられる。
     */
    private List<List<DayCell>> buildWeeks(
            YearMonth yearMonth,
            LocalDate gridStart,
            LocalDate gridEnd,
            List<ScheduleCalendarRepository.ScheduleSlot> slots
    ) {
        // 「日付|区分」をキーにして、その日その区分のスケジュールをすぐ引けるようにする
        // （例: "2026-07-06|1" → その日の午前のスケジュール）
        Map<String, ScheduleCalendarRepository.ScheduleSlot> slotMap = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            ScheduleCalendarRepository.ScheduleSlot slot = slots.get(i);
            String key = slot.scheduleDate() + "|" + slot.checkNo();
            slotMap.put(key, slot);
        }

        LocalDate today = LocalDate.now();

        List<List<DayCell>> weeks = new ArrayList<>();
        List<DayCell> currentWeek = new ArrayList<>();

        LocalDate date = gridStart;
        while (!date.isAfter(gridEnd)) {

            boolean inMonth = (date.getMonthValue() == yearMonth.getMonthValue());

            DayOfWeek dayOfWeek = date.getDayOfWeek();
            boolean weekend = (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY);

            boolean isToday = date.isEqual(today);

            CellSlot am = buildCellSlot(slotMap.get(date.toString() + "|1"));
            CellSlot pm = buildCellSlot(slotMap.get(date.toString() + "|2"));

            DayCell dayCell = new DayCell(
                    date.toString(),
                    date.getDayOfMonth(),
                    inMonth,
                    weekend,
                    isToday,
                    am,
                    pm
            );
            currentWeek.add(dayCell);

            // 日曜まで来たら1週分を確定して次の週へ
            if (dayOfWeek == DayOfWeek.SUNDAY) {
                weeks.add(currentWeek);
                currentWeek = new ArrayList<>();
            }

            date = date.plusDays(1);
        }

        return weeks;
    }

    /**
     * DBから取ったスケジュール1コマ分を、表示用のCellSlotに変換する。
     * 引数がnull（その日その区分は未登録）なら、空欄用のCellSlotを返す。
     */
    private CellSlot buildCellSlot(ScheduleCalendarRepository.ScheduleSlot slot) {
        if (slot == null) {
            return new CellSlot(false, false, null, null, null, null, null);
        }

        boolean holiday = "休み".equals(slot.status());
        if (holiday) {
            return new CellSlot(true, true, null, null, null, null, slot.memo());
        }

        return new CellSlot(
                true,
                false,
                slot.lessonType(),
                slot.className(),
                slot.teacherName(),
                slot.roomName(),
                slot.memo()
        );
    }
}
