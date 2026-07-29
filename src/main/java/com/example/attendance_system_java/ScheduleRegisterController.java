package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 週次スケジュール登録画面のController。
 * HTTPパラメータの受け取り・画面用データの組み立てだけを行い、
 * 業務判断（重複チェックや登録可否）はScheduleRegisterServiceに任せる。
 *
 * ■ Spring Boot初心者向けメモ（Controller全般の基礎）
 * ・@Controller は「このクラスは画面を返すControllerですよ」という目印。
 *   Spring起動時に自動でBean化され、対応するURLへのリクエストが来ると
 *   該当メソッドが呼ばれる。
 * ・@GetMapping("/schedule") はFlaskの @app.route("/schedule") とほぼ同じ意味。
 *   ブラウザが GET /schedule にアクセスした時にそのメソッドが呼ばれる。
 *   @PostMapping はPOST（フォーム送信）を受け取るためのもの。
 * ・@RequestParam は、URLのクエリパラメータやフォームの入力値を
 *   メソッドの引数として受け取るためのアノテーション。
 *   Flaskの request.args.get() / request.form.get() に相当する。
 * ・Model は、Thymeleafのテンプレート（schedule.html）に値を渡すための入れ物。
 *   model.addAttribute("名前", 値) で渡した値が、テンプレート内で ${名前} として使える。
 *   Flaskの render_template("schedule.html", 名前=値) に相当する。
 * ・戻り値の文字列 "schedule" は、テンプレート名（templates/schedule.html）を指す。
 *   "redirect:/schedule" のように redirect: を付けると、そのURLへブラウザを
 *   リダイレクトさせる（Flaskの redirect(url_for(...)) に相当）。
 * ・RedirectAttributes はリダイレクト先に一時的な情報を渡すための仕組み。
 *   addAttribute はURLのクエリパラメータとして付与され（?week=... の形で見える）、
 *   addFlashAttribute は画面には見えない形で1回だけ値を渡せる
 *   （このクラスでは登録成功メッセージを渡すのに使っている）。
 */
@Controller
public class ScheduleRegisterController {

    private final ScheduleRegisterRepository scheduleRepository;
    private final ScheduleRegisterService scheduleService;

    public ScheduleRegisterController(ScheduleRegisterRepository scheduleRepository, ScheduleRegisterService scheduleService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleService = scheduleService;
    }

    /**
     * 画面の1行分（例：7/6(月) 午前）
     * dayIndex は月曜=0〜日曜=6のその週の中での順番。
     */
    public record RowInfo(int dayIndex, int checkNo, String dateStr, String label, boolean weekend) {

        /**
         * 行の背景色を決めるCSSクラス名を返す（三項演算子ではなくif-elseで判定）。
         * dayIndex==5が土曜、weekendがtrueで土曜でなければ日曜。
         */
        public String rowClass() {
            if (weekend) {
                if (dayIndex == 5) {
                    return "row-saturday";
                }
                return "row-sunday";
            }
            if (checkNo == 1) {
                return "row-am";
            }
            return "row-pm";
        }
    }

    // 表示は月曜始まり（dayIndex 0=月 ... 6=日）
    private static final String[] DAY_LABELS = {"月", "火", "水", "木", "金", "土", "日"};

    // ---- 週の計算 ----

    /**
     * "2026-W28" 形式の文字列から、その週の月曜日を求める。
     * 形式が不正な場合は今週の月曜日を返す。
     *
     * これは <input type="week"> というHTMLのフォーム部品から送られてくる値の形式で、
     * 「2026年の第28週」を意味する。Javaの標準ライブラリ（java.time）には
     * この「西暦-W週番号」の形式を直接パースする機能が無いため、
     * 自前で文字列を分解し、WeekFields.ISO（ISO 8601という国際標準の週の数え方）を使って
     * 「その週の月曜日の日付」を求めている。
     */
    private LocalDate parseWeekToMonday(String weekValue) {
        try {
            // "2026-W28" を "-W" で分割して ["2026", "28"] にする
            String[] parts = weekValue.split("-W");
            int year = Integer.parseInt(parts[0]);
            int week = Integer.parseInt(parts[1]);

            // 1月4日は必ずその年の「第1週」に含まれるという ISO 8601 の性質を利用し、
            // そこを起点にして目的の週番号までずらし、最後に月曜日（dayOfWeek=1）に合わせる
            LocalDate base = LocalDate.of(year, 1, 4);
            base = base.with(WeekFields.ISO.weekOfWeekBasedYear(), week);
            base = base.with(WeekFields.ISO.dayOfWeek(), 1);
            return base;
        } catch (Exception e) {
            // weekValueが空文字や想定外の形式だった場合は、今週の月曜日を代わりに返す
            return LocalDate.now().with(DayOfWeek.MONDAY);
        }
    }

    /**
     * 今週を "2026-W28" 形式で返す（<input type="week"> の初期値として使う）
     */
    private String currentWeekValue() {
        LocalDate today = LocalDate.now();
        int year = today.get(WeekFields.ISO.weekBasedYear());
        int week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    /**
     * 月曜日を起点に、14行分（7日×午前午後）の行情報を作る。表示は月曜始まり。
     */
    private List<RowInfo> buildRows(LocalDate monday) {
        List<RowInfo> rows = new ArrayList<>();

        for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
            LocalDate date = monday.plusDays(dayIndex);
            boolean weekend = (dayIndex == 5 || dayIndex == 6);

            String dateLabel = date.getMonthValue() + "/" + date.getDayOfMonth()
                    + "(" + DAY_LABELS[dayIndex] + ")";

            // 1日につき「午前(check_no=1)」「午後(check_no=2)」の2行を作るので、
            // 7日 × 2 = 14行になる
            rows.add(new RowInfo(dayIndex, 1, date.toString(), dateLabel + " 午前", weekend));
            rows.add(new RowInfo(dayIndex, 2, date.toString(), dateLabel + " 午後", weekend));
        }
        return rows;
    }

    // ---- 画面表示 ----

    @GetMapping("/schedule")
    public String schedule(
            @RequestParam(name = "week", required = false) String weekValue,
            Model model
    ) {
        // week=... が指定されていなければ今週を表示する
        if (weekValue == null || weekValue.isBlank()) {
            weekValue = currentWeekValue();
        }

        LocalDate monday = parseWeekToMonday(weekValue);
        List<RowInfo> rows = buildRows(monday);
        List<ScheduleRegisterRepository.ClassInfo> classes = scheduleRepository.findActiveClasses();

        // 初期値を組み立てる（DB登録済み > デフォルト値 の優先順）
        Map<String, String> formValues = buildFormValues(monday, rows, classes);

        // ここで渡した値は、templates/schedule.html の中で
        // ${weekValue} や ${rows} のようにして参照できる
        model.addAttribute("weekValue", weekValue);
        model.addAttribute("rows", rows);
        model.addAttribute("classes", classes);
        model.addAttribute("teachers", scheduleRepository.findActiveTeachers());
        model.addAttribute("rooms", scheduleRepository.findActiveRooms());
        model.addAttribute("formValues", formValues);
        // 通常表示時は重複もエラーメッセージも無いので空で渡しておく
        // （こうしておくとThymeleaf側で毎回nullチェックをせずに済む）
        model.addAttribute("conflictCells", new HashSet<String>());
        model.addAttribute("errorMessages", new ArrayList<String>());

        return "schedule";
    }

    /**
     * フォームの初期値を作る。
     * DBに登録済みのコマはその内容、未登録のコマはデフォルト値
     * （土日=休み、平日=通常＋クラスのデフォルト場所＋学科）を入れる。
     *
     * 画面には「クラスID × 曜日インデックス × 午前午後」の組み合わせだけ数だけ
     * 入力欄が並ぶため、その1つ1つの初期値をあらかじめこのMapに詰めておき、
     * schedule.html側では formValues['status_xxx'] のように参照するだけで済むようにしている。
     */
    private Map<String, String> buildFormValues(
            LocalDate monday,
            List<RowInfo> rows,
            List<ScheduleRegisterRepository.ClassInfo> classes
    ) {
        Map<String, String> formValues = new HashMap<>();

        // 登録済みスケジュールを「classId_日付_区分」で引けるようにする
        String startDate = monday.toString();
        String endDate = monday.plusDays(6).toString();
        List<ScheduleRegisterRepository.ScheduleRow> saved = scheduleRepository.findSchedulesBetween(startDate, endDate);

        // DBから取ってきたList（順番に並んだ一覧）のままだと、
        // 「このクラス・この日・この区分の行はどれ？」を探すのに毎回全件ループが必要になる。
        // そこで先に「classId_日付_区分」というキー文字列 -> その行、というMapに詰め替えておくことで、
        // 後のループの中で savedMap.get(key) と一発で引けるようにしている（探索の高速化）。
        Map<String, ScheduleRegisterRepository.ScheduleRow> savedMap = new HashMap<>();
        for (ScheduleRegisterRepository.ScheduleRow row : saved) {
            String key = row.classId() + "_" + row.scheduleDate() + "_" + row.checkNo();
            savedMap.put(key, row);
        }

        // クラス × 曜日 × 午前午後 のすべての組み合わせ（=画面の全セル分）をループする
        for (ScheduleRegisterRepository.ClassInfo classInfo : classes) {
            for (RowInfo row : rows) {

                // suffix は画面のinput/selectのname属性の後半部分と一致させる文字列
                // （例："1_0_1" = classId=1, dayIndex=0(月曜), checkNo=1(午前)）
                String suffix = classInfo.classId() + "_" + row.dayIndex() + "_" + row.checkNo();
                // savedMapを引く時は「実際の日付文字列」がキーになっているため、
                // dayIndexではなくrow.dateStr()を使った別のキーを組み立てる
                String savedKey = classInfo.classId() + "_" + row.dateStr() + "_" + row.checkNo();

                ScheduleRegisterRepository.ScheduleRow savedRow = savedMap.get(savedKey);

                if (savedRow != null) {
                    // DB登録済みの内容を初期値にする
                    formValues.put("status_" + suffix, savedRow.status());

                    if (savedRow.lessonType() == null) {
                        formValues.put("lesson_" + suffix, "学科");
                    } else {
                        formValues.put("lesson_" + suffix, savedRow.lessonType());
                    }

                    if (savedRow.teacherId() == null) {
                        formValues.put("teacher_" + suffix, "");
                    } else {
                        formValues.put("teacher_" + suffix, savedRow.teacherId().toString());
                    }

                    if (savedRow.roomId() == null) {
                        formValues.put("room_" + suffix, "");
                    } else {
                        formValues.put("room_" + suffix, savedRow.roomId().toString());
                    }

                    if (savedRow.memo() == null) {
                        formValues.put("memo_" + suffix, "");
                    } else {
                        formValues.put("memo_" + suffix, savedRow.memo());
                    }
                } else {
                    // 未登録コマのデフォルト値
                    // 土日は「休み」、平日は「通常」＋そのクラスの標準の場所を初期値にする
                    if (row.weekend()) {
                        formValues.put("status_" + suffix, "休み");
                    } else {
                        formValues.put("status_" + suffix, "通常");
                    }

                    formValues.put("lesson_" + suffix, "学科");
                    formValues.put("teacher_" + suffix, "");

                    if (classInfo.defaultRoomId() == null) {
                        formValues.put("room_" + suffix, "");
                    } else {
                        formValues.put("room_" + suffix, classInfo.defaultRoomId().toString());
                    }

                    formValues.put("memo_" + suffix, "");
                }
            }
        }
        return formValues;
    }

    // ---- 登録処理 ----

    /**
     * @RequestParam Map<String, String> allParams と書くと、
     * フォームから送られてきた「name属性 -> 入力値」の組をすべてまとめて受け取れる。
     * 今回は "status_1_0_1" のように、name属性がクラスID・曜日・午前午後の組み合わせで
     * 動的に変化する（何個来るか事前に決め打ちできない）ため、
     * 1つ1つ個別の引数として受け取るのではなく、このMapでまとめて受け取っている。
     */
    @PostMapping("/schedule/submit")
    public String submit(
            @RequestParam Map<String, String> allParams,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        String weekValue = allParams.get("week");
        if (weekValue == null || weekValue.isBlank()) {
            weekValue = currentWeekValue();
        }

        LocalDate monday = parseWeekToMonday(weekValue);
        List<RowInfo> rows = buildRows(monday);
        List<ScheduleRegisterRepository.ClassInfo> classes = scheduleRepository.findActiveClasses();

        // フォーム内容を業務データ（CellEntry）へ変換する
        // ここが「Web（allParamsという生のフォームデータ）」から
        // 「業務データ（CellEntryという意味の分かる形）」への変換ポイントで、
        // これより先（ScheduleRegisterServiceの中）はHTTPやフォームのことを一切気にしなくてよくなる
        List<ScheduleRegisterService.CellEntry> entries = new ArrayList<>();
        List<String> inputErrors = new ArrayList<>();
        Set<String> inputErrorCells = new HashSet<>();

        for (ScheduleRegisterRepository.ClassInfo classInfo : classes) {
            for (RowInfo row : rows) {

                String suffix = classInfo.classId() + "_" + row.dayIndex() + "_" + row.checkNo();
                String cellKey = suffix;

                String status = allParams.get("status_" + suffix);
                if (status == null) {
                    // 通常は起こらないが、想定外にこのセルのデータが送られてこなかった場合はスキップ
                    continue;
                }

                String lessonType = allParams.get("lesson_" + suffix);
                Long teacherId = parseLongOrNull(allParams.get("teacher_" + suffix));
                Long roomId = parseLongOrNull(allParams.get("room_" + suffix));
                String memo = allParams.get("memo_" + suffix);

                if (status.equals("休み")) {
                    entries.add(new ScheduleRegisterService.CellEntry(
                            cellKey, classInfo.classId(), classInfo.className(),
                            row.dateStr(), row.checkNo(),
                            "休み", null, null, null, memo, row.label()
                    ));
                    continue;
                }

                // 通常コマ：教師も場所も未選択なら「まだ決まっていないコマ」として登録しない
                if (teacherId == null && roomId == null) {
                    continue;
                }

                // 片方だけ選択されている場合は入力エラーにする
                // （教師だけ決まって場所が未定、のような中途半端な状態でDBに保存させないため）
                if (teacherId == null || roomId == null) {
                    inputErrors.add(row.label() + "（" + classInfo.className() + "）：教師と場所の両方を選択してください");
                    inputErrorCells.add(cellKey);
                    continue;
                }

                entries.add(new ScheduleRegisterService.CellEntry(
                        cellKey, classInfo.classId(), classInfo.className(),
                        row.dateStr(), row.checkNo(),
                        "通常", lessonType, teacherId, roomId, memo, row.label()
                ));
            }
        }

        // 入力エラー（片方だけ選択、等）があれば、DB問い合わせをするまでもなく登録せずに画面へ戻す
        if (!inputErrors.isEmpty()) {
            return renderWithErrors(model, weekValue, rows, classes, allParams, inputErrors, inputErrorCells);
        }

        // 業務チェック＋登録（重複があれば全体が登録されない）
        // ここから先の判断はすべてScheduleRegisterServiceに任せる。
        // 登録は「対象週×対象クラスの範囲を消して入れ直す」方式のため、
        // 消す範囲を正しく指定できるよう、クラスID一覧と週の開始日・終了日も一緒に渡す
        List<Long> classIds = new ArrayList<>();
        for (ScheduleRegisterRepository.ClassInfo classInfo : classes) {
            classIds.add(classInfo.classId());
        }

        String weekStart = monday.toString();
        String weekEnd = monday.plusDays(6).toString();

        ScheduleRegisterService.RegisterResult result = scheduleService.registerWeek(entries, classIds, weekStart, weekEnd);

        if (!result.isSuccess()) {
            return renderWithErrors(model, weekValue, rows, classes, allParams,
                    result.getErrorMessages(), result.getConflictCells());
        }

        // 登録成功時はPOST結果をそのまま画面表示せず、GET /schedule へリダイレクトする
        // （これをしないと、ブラウザの「再読み込み」で二重登録されてしまう可能性があるため。
        //   PRG（Post-Redirect-Get）パターンと呼ばれる定番のやり方）
        redirectAttributes.addAttribute("week", weekValue);
        redirectAttributes.addFlashAttribute("successMessage", "スケジュールを登録しました");
        return "redirect:/schedule";
    }

    /**
     * エラー時に、入力内容を保持したまま画面を再表示する
     */
    private String renderWithErrors(
            Model model,
            String weekValue,
            List<RowInfo> rows,
            List<ScheduleRegisterRepository.ClassInfo> classes,
            Map<String, String> allParams,
            List<String> errorMessages,
            Set<String> conflictCells
    ) {
        model.addAttribute("weekValue", weekValue);
        model.addAttribute("rows", rows);
        model.addAttribute("classes", classes);
        model.addAttribute("teachers", scheduleRepository.findActiveTeachers());
        model.addAttribute("rooms", scheduleRepository.findActiveRooms());
        // formValuesには、DBの内容ではなく「ユーザーが今回入力した内容(allParams)」をそのまま渡す。
        // こうすることで、エラーになって画面に戻っても入力し直しにならずに済む
        model.addAttribute("formValues", allParams);
        model.addAttribute("conflictCells", conflictCells);
        model.addAttribute("errorMessages", errorMessages);

        return "schedule";
    }

    /**
     * 文字列をLongに変換する。空文字やnull、数値でない文字列の場合はnullを返す
     * （教師・場所の選択が「未選択」だった場合、フォームからは空文字が送られてくるため）
     */
    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
