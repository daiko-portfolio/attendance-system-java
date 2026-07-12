package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出欠登録画面のController。
 * 3層構成（Controller / Service / Repository）に分けた最初の画面。
 *
 * ・単純な一覧取得（教室一覧、生徒一覧、登録済み出席時間）は
 *   業務判断が無いので、Controllerから直接RegisterRepositoryを呼んでいる。
 * ・「出席時間から出席/欠席を判定して保存する」という業務ロジックは
 *   RegisterServiceに任せている。
 *
 * SQLException（チェック例外）はRegisterRepository側でキャッチしてRuntimeException（実行時例外）に
 * 変換しているため、このControllerはthrowsを書かずに済んでいる。
 * RuntimeExceptionは投げる場所を宣言する必要が無い例外の種類で、
 * ここで何もしなくても、エラーが起きればSpring Bootの標準エラー画面が表示される。
 */
@Controller
public class RegisterController {

    private final RegisterRepository registerRepository;
    private final RegisterService registerService;

    public RegisterController(RegisterRepository registerRepository, RegisterService registerService) {
        this.registerRepository = registerRepository;
        this.registerService = registerService;
    }

    @GetMapping("/register")
    public String register(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "date", required = false) String selectedDate,
            @RequestParam(name = "check_no", required = false) String selectedCheckNo,
            @RequestParam(name = "lesson_type", required = false) String selectedLessonType,
            Model model
    ) {
        if (selectedDate == null || selectedDate.isBlank()) {
            selectedDate = LocalDate.now().toString();
        }

        // 区分が指定されていなければ、今の時刻から午前/午後を自動判定する
        // （正午より前なら午前、正午以降なら午後）
        if (selectedCheckNo == null || selectedCheckNo.isBlank()) {
            int currentHour = LocalTime.now().getHour();
            if (currentHour < 12) {
                selectedCheckNo = "1";
            } else {
                selectedCheckNo = "2";
            }
        }

        if (selectedLessonType == null || selectedLessonType.isBlank()) {
            selectedLessonType = "学科";
        }

        List<RegisterRepository.ClassOption> classes = registerRepository.findActiveClasses();

        List<RegisterRepository.PersonOption> persons = List.of();
        String className = null;
        // person_id -> 登録済みの出席時間（3/2/1/0）。ラジオボタンの初期選択に使う
        Map<Long, Integer> currentHours = new HashMap<>();
        // その日の授業スケジュール（未登録ならnullのまま）
        RegisterRepository.ScheduleInfo scheduleInfo = null;

        if (selectedClassId != null && !selectedClassId.isBlank()) {

            persons = registerRepository.findPersonsByClassId(selectedClassId);

            for (RegisterRepository.ClassOption c : classes) {
                if (String.valueOf(c.classId()).equals(selectedClassId)) {
                    className = c.className();
                    break;
                }
            }

            currentHours = registerRepository.findCurrentHours(selectedDate, selectedCheckNo);

            // その教室・日付・区分の授業スケジュールを取得し、参考情報として画面に渡す
            scheduleInfo = registerRepository.findScheduleInfo(selectedClassId, selectedDate, selectedCheckNo);

            // 授業属性の初期値は、確度の高い順に決める
            //   1. 出欠として登録済みの属性（実際に取った出欠が一番確実）
            //   2. スケジュールに登録された属性（授業予定から引き継ぐ）
            //   3. どちらも無ければ検索フォームの値（デフォルトは学科）のまま
            String registeredLessonType = registerRepository.findCurrentLessonType(selectedClassId, selectedDate, selectedCheckNo);
            if (registeredLessonType != null && !registeredLessonType.isBlank()) {
                selectedLessonType = registeredLessonType;
            } else if (scheduleInfo != null && scheduleInfo.lessonType() != null) {
                selectedLessonType = scheduleInfo.lessonType();
            }
        }

        model.addAttribute("classes", classes);
        model.addAttribute("persons", persons);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("className", className);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("selectedCheckNo", selectedCheckNo);
        model.addAttribute("selectedLessonType", selectedLessonType);
        model.addAttribute("currentHours", currentHours);
        model.addAttribute("scheduleInfo", scheduleInfo);

        return "register";
    }

    /**
     * 生徒1人1人につき "attended_hours_<person_id>" という名前のラジオボタンが
     * 動的に並ぶ画面のため、@RequestParam Map<String, String> でフォームの中身を
     * まるごと受け取り、名前のプレフィックス（前方一致）で目的のパラメータだけを拾い出している。
     */
    @PostMapping("/attendance/submit")
    public String submitAttendance(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String classId = allParams.get("class_id");
        String attendanceDate = allParams.get("attendance_date");
        String checkNo = allParams.get("check_no");
        String lessonType = allParams.get("lesson_type");

        // フォームの全パラメータの中から "attended_hours_" で始まるものだけを拾い、
        // 残りの文字列（person_id）を取り出してServiceに渡すDTOのListを組み立てる
        List<RegisterService.PersonHours> entries = new ArrayList<>();

        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("attended_hours_")) {
                String personIdText = key.substring("attended_hours_".length());
                long personId = Long.parseLong(personIdText);
                int attendedHours = Integer.parseInt(entry.getValue());

                entries.add(new RegisterService.PersonHours(personId, attendedHours));
            }
        }

        registerService.registerAttendance(attendanceDate, checkNo, lessonType, entries);

        redirectAttributes.addAttribute("date", attendanceDate);
        redirectAttributes.addAttribute("class_id", classId);

        return "redirect:/list";
    }
}
