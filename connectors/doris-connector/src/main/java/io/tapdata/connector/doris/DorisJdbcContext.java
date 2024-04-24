package io.tapdata.connector.doris;

import io.tapdata.connector.doris.bean.DorisConfig;
import io.tapdata.connector.mysql.MysqlJdbcContextV2;
import io.tapdata.connector.mysql.config.MysqlConfig;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.kit.EmptyKit;

import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author jarad
 * @date 7/14/22
 */
public class DorisJdbcContext extends MysqlJdbcContextV2 {

    public DorisJdbcContext(DorisConfig dorisConfig) {
        super(dorisConfig);
        exceptionCollector = new DorisExceptionCollector();
    }

    public String queryVersion() throws SQLException {
        AtomicReference<String> version = new AtomicReference<>();
        queryWithNext(DORIS_VERSION, resultSet -> version.set(resultSet.getString("Value")));
        return version.get();
    }

    public List<TapTable> queryTablesDesc(List<String> tableNames) throws SQLException {
        List<TapTable> tableList = new ArrayList<>();
        for (String table : tableNames) {
            TapTable tapTable = new TapTable(table);
            AtomicInteger fieldPos = new AtomicInteger(0);
            AtomicInteger keyPos = new AtomicInteger(0);
            query(String.format(DORIS_SHOW_COLUMNS, table), resultSet -> {
                while (resultSet.next()) {
                    TapField tapField = new TapField(resultSet.getString("Field"), resultSet.getString("Type").toLowerCase());
                    tapField.setPos(fieldPos.incrementAndGet());
                    tapField.setComment(resultSet.getString("Comment"));
                    tapField.setNullable("YES".equals(resultSet.getString("Null")));
                    if ("YES".equals(resultSet.getString("Key"))) {
                        tapField.setPrimaryKey(true);
                        tapField.setPrimaryKeyPos(keyPos.incrementAndGet());
                    }
                    tapTable.add(tapField);
                }
            });
            query(String.format(DORIS_SHOW_INDEX, table), resultSet -> {
                while (resultSet.next()) {
                    TapIndex tapIndex = new TapIndex();
                    tapIndex.name(resultSet.getString("Key_name"));
                    tapIndex.setUnique(false);
                    tapIndex.setPrimary(false);
                    tapIndex.indexField(new TapIndexField().name(resultSet.getString("Column_name")).fieldAsc(true));
                    tapTable.add(tapIndex);
                }
            });
            tableList.add(tapTable);
        }
        return tableList;
    }

    public TimeZone queryTimeZone() throws SQLException {
        if (EmptyKit.isNotBlank(((DorisConfig) getConfig()).getTimezone())) {
            return TimeZone.getTimeZone(ZoneId.of(((DorisConfig) getConfig()).getTimezone()));
        }
        AtomicReference<Long> timeOffset = new AtomicReference<>();
        queryWithNext(DORIS_TIMEZONE, resultSet -> timeOffset.set(resultSet.getLong(1)));
        DecimalFormat decimalFormat = new DecimalFormat("00");
        if (timeOffset.get() >= 0) {
            return TimeZone.getTimeZone(ZoneId.of("+" + decimalFormat.format(timeOffset.get()) + ":00"));
        } else {
            return TimeZone.getTimeZone(ZoneId.of(decimalFormat.format(timeOffset.get()) + ":00"));
        }
    }
    private static final String DORIS_VERSION = "show variables like '%version_comment%'";
    private static final String DORIS_SHOW_COLUMNS = "show full columns from `%s`";
    private static final String DORIS_SHOW_INDEX = "show index from `%s`";

    public final static String DORIS_TIMEZONE = "SELECT TIMESTAMPDIFF(HOUR, UTC_TIMESTAMP(), NOW()) as timeoffset";
}
