package com.example.attendance_system_java;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ポートフォリオ公開用：ボタン1つで既存データを全削除し、
 * デモ用のサンプルデータ（2026年8月分）を投入し直す機能。
 *
 * テーブルは起動時にDatabaseInitializerが既に作成済みのため、ここではDROP/CREATEはせず、
 * 全テーブルのDELETE（＋AUTOINCREMENT採番のリセット）とINSERTだけを行う。
 * classpath上の sample_data.sql（DELETE〜INSERTが";"区切りで並んでいるだけの
 * 単純なファイル）を文単位に分割して、JdbcTemplateで順番に実行するだけの処理。
 * DatabaseInitializerと同様、業務判断のないDBセットアップ処理なので
 * 生JDBCではなくJdbcTemplateを使っている。
 */
@Service
public class SampleDataService {

    private static final String SQL_FILE = "sample_data.sql";

    private final JdbcTemplate jdbcTemplate;

    public SampleDataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void load() {
        String sqlFileContent = readSqlFile();

        String[] statements = sqlFileContent.split(";");
        for (String statement : statements) {
            String trimmedStatement = statement.trim();
            if (!trimmedStatement.isEmpty()) {
                jdbcTemplate.execute(trimmedStatement);
            }
        }
    }

    private String readSqlFile() {
        try (InputStream inputStream = new ClassPathResource(SQL_FILE).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
