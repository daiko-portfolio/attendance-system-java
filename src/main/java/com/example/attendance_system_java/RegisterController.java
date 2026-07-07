package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.sql.SQLException;
import java.time.LocalDate;
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
 * ここのメソッドは throws SQLException をそのまま書いている。
 * SQLExceptionは「チェック例外」と呼ばれる種類で、投げる可能性がある場合は
 * メソッドの宣言に throws を書く必要がある（C#の例外は書かなくてもいいので、この点はJava特有）。
 * 今回は独自の例外処理を作らず、そのままSpringに任せて素通しする方針にしている
 * （エラーが起きた場合はSpring Bootの標準エラー画面が表示される）。
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
            @RequestParam(name = "check_no", required = false, defaultValue = "1") String selectedCheckNo,
            Model model
    ) throws SQLException {
        if (selectedDate == null || selectedDate.isBlank()) {
            selectedDate = LocalDate.now().toString();
        }

        List<RegisterRepository.ClassOption> classes = registerRepository.findActiveClasses();

        List<RegisterRepository.PersonOption> persons = List.of();
        String className = null;
        // person_id -> 登録済みの出席時間（3/2/1/0）。ラジオボタンの初期選択に使う
        Map<Long, Integer> currentHours = new HashMap<>();

        if (selectedClassId != null && !selectedClassId.isBlank()) {

            persons = registerRepository.findPersonsByClassId(selectedClassId);

            for (RegisterRepository.ClassOption c : classes) {
                if (String.valueOf(c.classId()).equals(selectedClassId)) {
                    className = c.className();
                    break;
                }
            }

            currentHours = registerRepository.findCurrentHours(selectedDate, selectedCheckNo);
        }

        model.addAttribute("classes", classes);
        model.addAttribute("persons", persons);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("className", className);
        model.addAttribute("today", selectedDate);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("selectedCheckNo", selectedCheckNo);
        model.addAttribute("currentHours", currentHours);

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
    ) throws SQLException {
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
