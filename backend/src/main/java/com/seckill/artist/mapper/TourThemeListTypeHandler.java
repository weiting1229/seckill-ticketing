package com.seckill.artist.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.artist.domain.TourTheme;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * {@code artists.tour_themes}(JSONB)↔ {@code List<TourTheme>} 的轉換。
 *
 * <p><b>寫入端必須是 {@link Types#OTHER} 而不是 {@code setString}。</b> 直接 setString 會被
 * PostgreSQL 以
 * {@code column "tour_themes" is of type jsonb but expression is of type character varying}
 * 拒絕 —— 那是啟動後第一次匯入才會炸的執行期錯誤,單元測試看不到。{@code Types.OTHER} 讓
 * 驅動送出「未指定型別」的參數,由伺服器依目標欄位推斷成 jsonb。
 *
 * <p>刻意<b>不</b>用 {@code org.postgresql.util.PGobject}:pom 裡 postgresql 驅動是
 * {@code runtime} scope(應用程式碼不該編譯依賴特定驅動),import 它等於把 scope 提升成
 * compile。{@code Types.OTHER} 是同樣效果的 JDBC 標準寫法。
 *
 * <p>TypeHandler 由 MyBatis 自行實例化(不經 Spring),因此 {@link ObjectMapper} 用 static
 * 常數而非注入。這裡只做「陣列 of {zh,en}」的轉換,不需要專案共用的 Jackson 客製設定。
 */
@MappedTypes(List.class)
public class TourThemeListTypeHandler extends BaseTypeHandler<List<TourTheme>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<TourTheme>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<TourTheme> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, write(parameter), Types.OTHER);
    }

    @Override
    public List<TourTheme> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getString(columnName));
    }

    @Override
    public List<TourTheme> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getString(columnIndex));
    }

    @Override
    public List<TourTheme> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getString(columnIndex));
    }

    private static String write(List<TourTheme> themes) throws SQLException {
        try {
            return MAPPER.writeValueAsString(themes == null ? List.of() : themes);
        } catch (Exception e) {
            throw new SQLException("tour_themes 序列化失敗", e);
        }
    }

    /** 欄位是 NOT NULL DEFAULT '[]',但舊列或手動改過的資料仍可能是 null —— 一律回空陣列而非 null。 */
    private static List<TourTheme> read(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            throw new SQLException("tour_themes 反序列化失敗:" + json, e);
        }
    }
}
