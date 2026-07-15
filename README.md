# 出欠管理システム（Spring Boot版）

以前Flask + SQLiteで作った出欠管理システムを、Spring Boot + Thymeleaf + SQLiteへ移植したものです。

もともとの目的は「Flaskで作った業務支援ツールを、実務でよく使われるJava/Spring Bootで書き直すとどうなるか」を学ぶための移植プロジェクトです。移植が完了した後、Flask版には無かった「授業スケジュール登録機能」をSpring Boot側だけの新機能として追加しています。

## 現在できること

Flask版から移植した機能:

- 教室・受講者の登録・管理
- 教室、日付、午前/午後ごとの出欠一括登録（登録済みデータがあれば選択状態を復元）
- 出席時間の登録（3h / 2h / 1h / 0h）、授業属性の登録（学科/実技）
- 出欠一覧の表示（日付・教室・名前で検索）
- 月次出欠表の表示（学科/実技/総合の出席時間集計、6h=1日換算）
- 出席率サマリー（学科/実技/総合ごとに安全/注意/危険を判定）
- 登録済み出欠の編集・削除

Spring Boot版で新規追加した機能:

- **授業スケジュール登録**（週単位で登録）
  - 縦軸：1週間×午前午後（14行）、横軸：クラス（コース）
  - 教師・使用場所・授業属性・コメントをコマごとに登録
  - 土日はデフォルトで「休み」、平日祝日は手動で「休み」に切り替え可能
  - 同じ教師・同じ場所が同じ時間帯に重複登録されないよう、DBのUNIQUE制約＋事前チェックで排他制御
  - 重複があった場合はその週全体を登録せず、該当セルを赤くハイライトして再入力を促す
- **授業スケジュールの月次一覧表示**（教室軸・教師軸の2種類）
- **サンプルデータ投入**（メニュー下部のボタン1つで、既存データを全削除して2026年8月分のデモデータを再投入。ポートフォリオとして初見でも中身のあるデータを見てもらうための機能）

## 未移植・未実装（Flask版にあってSpring Boot版に無いもの）

- 出欠一覧のCSV出力（`/list/csv`）
- 教師・場所マスタの編集画面（現在は初期データ登録のみ、コード上での追加はできるがUIが無い）

## 画面一覧

| URL | 内容 |
|---|---|
| `/` | メニュー |
| `/register` | 出欠登録 |
| `/list` | 出欠一覧 |
| `/monthly` | 月次出欠表 |
| `/summary` | 出席率サマリー |
| `/edit` | 出欠編集・削除 |
| `/schedule` | 授業スケジュール登録（週単位） |
| `/schedule/monthly` | スケジュール月次一覧（教室軸） |
| `/schedule/monthly/teacher` | スケジュール月次一覧（教師軸） |
| `/class/create` | 新教室作成 |
| `/classes` | 教室管理 |
| `/persons` | 受講者管理 |
| `/teachers` | 教師管理 |
| `/rooms` | 部屋管理 |
| `/sample-data/load`（POST） | サンプルデータ投入 |

## 使用技術

- Java 17
- Spring Boot（Spring Web, Thymeleaf, JDBC）
- SQLite（`sqlite-jdbc`ドライバ経由で、生JDBCまたはJdbcTemplateから直接SQLを実行。JPA/Hibernateは不使用）
- Maven
- HTML/CSS

## アーキテクチャ

Flaskからの移植を優先した画面（出欠登録〜受講者管理の一部）は、**Controller内にJdbcTemplateでSQLを直書き** するシンプルな構成のまま残しています。

新機能（授業スケジュール登録・スケジュール月次一覧・サンプルデータ投入等）は、Spring Bootの標準的な層構成と生JDBCを学ぶ目的も兼ねて **Controller / Service / Repository** の3層＋生JDBC（`Connection`/`PreparedStatement`/`ResultSet`のtry-with-resources）で実装しています。ただし業務判断のない単純な一覧取得はService層を挟まずController→Repository直結にする等、画面の性質に応じて層を省略している箇所もあります。

```
ScheduleController  … HTTPの受け取り、フォーム⇔業務データの変換、画面表示
ScheduleService     … 業務ロジック（重複チェック、トランザクション管理）
ScheduleRepository  … DBアクセス（生JDBC）
```

重複チェックはアプリ側の事前チェックに加え、最終的にはDBのUNIQUE制約が排他制御の担保になっています（アプリ側のチェックをすり抜けても、DBが確実に拒否する二重の安全策）。

## フォルダ構成

```text
attendance-system-java/
├── pom.xml
├── data/
│   └── attendance.db          … SQLiteのDBファイル（実行時に読み書きする場所）
├── src/main/java/com/example/attendance_system_java/
│   ├── AttendanceSystemJavaApplication.java
│   ├── DatabaseInitializer.java   … 起動時にテーブル作成・初期データ投入
│   ├── IndexController.java
│   ├── RegisterController.java
│   ├── ListController.java
│   ├── MonthlyController.java
│   ├── SummaryController.java
│   ├── EditController.java
│   ├── ClassController.java
│   ├── PersonController.java
│   ├── ScheduleController.java    … スケジュール機能（Controller層）
│   ├── ScheduleService.java       … スケジュール機能（Service層）
│   └── ScheduleRepository.java    … スケジュール機能（Repository層）
└── src/main/resources/
    ├── application.properties
    ├── static/style.css
    └── templates/
        ├── index.html, register.html, list.html, monthly.html,
        │   summary.html, edit.html, class_create.html, classes.html, persons.html
        └── schedule.html
```

## セットアップ

VS Codeで「Spring Boot Extension Pack」を導入した状態を想定しています。

### ターミナルから起動する場合

```powershell
cd JAVA_attendance_system\attendance-system-java
.\mvnw.cmd spring-boot:run
```

### VS CodeのSpring Boot Dashboardから起動する場合

左サイドバーの「Spring Boot Dashboard」パネルからプロジェクトを選び、▶ボタンで起動します。

起動後、ブラウザで `http://localhost:8080/` を開きます。

## DB概要

DBはSQLiteの `data/attendance.db` です。起動時（`DatabaseInitializer`）に、無ければテーブルを自動作成します。

| テーブル | 内容 |
|---|---|
| `classes` | 教室/訓練クラス情報（`default_room_id`列でスケジュールの初期場所を保持） |
| `persons` | 受講者情報 |
| `attendance` | 日付・午前午後・受講者ごとの出欠情報 |
| `teachers` | 教師マスタ（起動時に初期データ投入） |
| `rooms` | 使用場所マスタ（起動時に初期データ投入） |
| `schedules` | 授業スケジュール（クラス・日付・午前午後ごとの教師・場所・属性・コメント） |

`schedules` テーブルには排他制御のための3つのUNIQUE制約があります。

```sql
UNIQUE (class_id, schedule_date, check_no)   -- 同じクラスの同じコマは1つだけ
UNIQUE (teacher_id, schedule_date, check_no) -- 同じ教師が同じ時間帯に2箇所に入れない
UNIQUE (room_id, schedule_date, check_no)    -- 同じ場所が同じ時間帯に二重予約されない
```

SQLiteのUNIQUE制約はNULL同士を別物として扱うため、「休み」の行（教師・場所がNULL）は何行あっても制約に引っかかりません。

## 今後の改善案

- CSV出力機能の移植
- 教師・場所マスタの編集画面
- テストコードの追加
