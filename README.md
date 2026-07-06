# Flask出欠管理システム

職業訓練校の教室単位の出欠登録、一覧確認、月次集計を行うためのFlask + SQLite製Webアプリです。

FE合格後に初めて作成した業務支援ツールで、午前・午後の授業枠ごとに出欠時間を登録し、学科・実技別の出席時間や出席率を確認できることを目的にしています。

## 現在できること

- 教室と受講者の登録・管理
- 教室、日付、午前/午後ごとの出欠一括登録
- 出席時間の登録
  - 3h
  - 2h
  - 1h
  - 0h
- 授業属性の登録
  - 学科
  - 実技
- 出欠一覧の表示
- 出欠一覧のCSV出力
- 月次出欠表の表示
- 学科/実技/総合の出席時間集計
- 出席率による判定表示
  - 安全
  - 注意
  - 危険
- 登録済み出欠の編集・削除

## 画面一覧

| URL | 内容 |
|---|---|
| `/` | メニュー |
| `/register` | 出欠登録 |
| `/list` | 出欠一覧 |
| `/list/csv` | 出欠一覧CSV出力 |
| `/monthly` | 月次出欠表 |
| `/summary` | 出席時間集計 |
| `/edit` | 出欠編集・削除 |
| `/class/create` | 新教室作成 |
| `/classes` | 教室管理 |
| `/persons` | 受講者管理 |

## 使用技術

- Python
- Flask
- SQLite
- Jinja2
- HTML/CSS

## フォルダ構成

```text
attendance_system/
├── app.py
├── attendance.db
├── attendance20260604.db
├── requirements.txt
├── static/
│   └── style.css
├── templates/
│   ├── base.html
│   ├── index.html
│   ├── register.html
│   ├── list.html
│   ├── monthly.html
│   ├── summary.html
│   ├── edit.html
│   ├── class_create.html
│   ├── classes.html
│   └── persons.html
└── OLD/
```

## セットアップ

PowerShellでプロジェクトフォルダに移動します。

```powershell
cd C:\Users\daida\Documents\FLASK出欠管理システム\attendance_system
```

仮想環境を作成して有効化します。

```powershell
python -m venv venv
.\venv\Scripts\Activate.ps1
```

PowerShellで仮想環境を有効化できない場合は、必要に応じて実行ポリシーを変更します。

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

依存関係をインストールします。

```powershell
pip install -r requirements.txt
```

アプリを起動します。

```powershell
python app.py
```

## DB概要

現在のDBはSQLiteの `attendance.db` です。主なテーブルは次の3つです。

| テーブル | 内容 |
|---|---|
| `classes` | 教室/訓練クラス情報 |
| `persons` | 受講者情報 |
| `attendance` | 日付・午前午後・受講者ごとの出欠情報 |

主なリレーションは次の通りです。

- `classes.class_id` -> `persons.class_id`
- `persons.person_id` -> `attendance.person_id`

`attendance` には `UNIQUE(attendance_date, check_no, person_id)` があり、同じ日付・午前午後・受講者の出欠が重複登録されないようになっています。登録済みの出欠は `ON CONFLICT DO UPDATE` で更新されます。

## 現在のDB設計で良い点

- 教室、受講者、出欠がテーブルとして分かれている
- 受講者番号は教室内で一意になるようにしている
- 出欠は日付、午前午後、受講者で一意になっている
- 外部キーを使って教室と受講者、受講者と出欠を関連付けている
- 学科/実技、出席時間の制約をDB側にも持たせている

## 今後の改善案

ポートフォリオとして強くするなら、次は出欠登録と授業スケジュールを分離するのがおすすめです。

### 1. スケジュールテーブルを追加する

現在は、出欠登録時に `lesson_type` を登録しています。今後は `schedules` テーブルを追加し、授業枠を先に登録してから、その授業枠に対して出欠を取る形にすると業務アプリらしさが増します。

例:

| カラム | 内容 |
|---|---|
| `schedule_id` | 授業枠ID |
| `class_id` | 対象教室/クラス |
| `schedule_date` | 授業日 |
| `check_no` | 午前/午後 |
| `lesson_type` | 学科/実技 |
| `lesson_title` | 授業内容 |
| `teacher_id` | 担当教師 |
| `room_id` | 使用教室 |
| `status` | 通常/休講/授業なし |

### 2. 教師・物理教室をマスタ化する

`teachers` と `rooms` を作ると、担当教師や使用教室の重複チェックができます。

- 同じ教師が同じ日・同じ時間帯に複数授業へ入らない
- 同じ物理教室が同じ日・同じ時間帯に二重予約されない
- 同じ訓練クラスに同じ時間帯の授業が二重登録されない

### 3. 「授業なし日」を明示的に登録する

何も登録しない状態だと「未登録」なのか「授業なし」なのか判別しにくくなります。`schedules.status` に `no_class` を持たせると、月次表や管理画面で意図が明確になります。

### 4. `classes` と `rooms` を分ける

現在の `classes` は「A教室」「B教室」のような名前ですが、役割としては訓練クラス/コースに近いです。物理的な部屋を管理する場合は `rooms` を別テーブルにした方が、スケジュール競合防止が実装しやすくなります。

### 5. DBマイグレーションを導入する

現在は `CREATE TABLE IF NOT EXISTS` で初期化しています。機能追加が増える場合は、DB変更履歴を管理するためにマイグレーション手順を用意すると安全です。

## ポートフォリオ化に向けた整理

- `README.md` を整備する
- サンプルデータ作成手順を用意する
- `venv` や `__pycache__` はGit管理対象から外す
- `requirements.txt` の未使用ライブラリを整理する
- 主要画面のスクリーンショットをREADMEに載せる
- テストコードを追加する
- DB設計資料を添付する

## 確認済み事項

- `app.py` の構文チェックは通過済み
- `attendance.db` に以下のデータが存在することを確認済み
  - `classes`: 3件
  - `persons`: 45件
  - `attendance`: 80件
- `attendance.lesson_type` は実データ上で `学科` と `実技` が登録済み
- `attendance.status` は実データ上で `出席` と `欠席` が登録済み

