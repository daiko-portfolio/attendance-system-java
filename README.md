# 出欠管理システム（Spring Boot版）

職業訓練校向けの出欠管理システムです。Java / Spring Boot / Thymeleaf / SQLiteで作っています。

出欠の登録・検索・月次集計・出席率判定に加えて、授業スケジュール（教師・部屋の割当）の管理もできます。

![メニュー画面](docs/images/menu.png)

## できること

- 教室・受講者の登録・管理
- 教室、日付、午前/午後ごとの出欠一括登録（登録済みデータがあれば選択状態を復元）
- 出席時間の登録（3h / 2h / 1h / 0h）、授業属性の登録（学科/実技）
- 出欠一覧の表示（日付・教室・名前で検索）
- 月次出欠表の表示（学科/実技/総合の出席時間集計、6h=1日換算）
- 出席率サマリー（学科/実技/総合ごとに安全/注意/危険を判定）
- 登録済み出欠の編集・削除
- 授業スケジュール登録（週単位）
  - 縦軸：1週間×午前午後（14行）、横軸：クラス（コース）
  - 教師・使用場所・授業属性・コメントをコマごとに登録
  - 土日はデフォルトで「休み」、平日祝日は手動で切り替え可能
  - 同じ教師・同じ場所が同じ時間帯に重複しないよう、DBのUNIQUE制約＋事前チェックで排他制御
  - 重複していた場合はその週全体を登録せず、該当セルを赤くハイライトして再入力を促す
- 授業スケジュールの月次一覧表示（教室軸・教師軸）
- サンプルデータ投入（メニュー下部のボタン1つで、既存データを全部消して2026年8月分のデモデータを入れ直す。中身が空だと寂しいので用意した）

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

## スクリーンショット

出欠登録

![出欠登録](docs/images/register.png)

出欠一覧

![出欠一覧](docs/images/list.png)

月次出欠表

![月次出欠表](docs/images/monthly.png)

出席率サマリー

![出席率サマリー](docs/images/summary.png)

教室管理

![教室管理](docs/images/classes.png)

スケジュール月次一覧（教室軸）

![スケジュール月次一覧（教室軸）](docs/images/schedule-room.png)

スケジュール月次一覧（教師軸）

![スケジュール月次一覧（教師軸）](docs/images/schedule-teacher.png)

## 使用技術

- Java 17
- Spring Boot（Spring Web, Thymeleaf, JDBC）
- SQLite（`sqlite-jdbc`ドライバ経由。JPA/Hibernateは不使用）
- Maven
- HTML/CSS

## アーキテクチャ

古い画面（出欠登録〜受講者管理の一部）は、Controller内にJdbcTemplateでSQLを直書きするシンプルな構成のままにしています。

新しめの機能（スケジュール登録・月次一覧・サンプルデータ投入）は、Controller / Service / Repositoryの3層＋生JDBC（`Connection`/`PreparedStatement`/`ResultSet`のtry-with-resources）で書いています。層構成やJDBCの書き方を学びながら進めているので、画面によって書き方の新旧が混在しているのはそのためです。ただし業務判断のない単純な一覧取得はService層を挟まずController→Repository直結にしています。

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

## 今後の追加予定

- 出欠一覧のCSV出力（`/list/csv`）
- 教師・場所マスタの編集画面（現在は初期データ登録のみ、コード上で追加はできるがUIが無い）
- テストコードの追加
