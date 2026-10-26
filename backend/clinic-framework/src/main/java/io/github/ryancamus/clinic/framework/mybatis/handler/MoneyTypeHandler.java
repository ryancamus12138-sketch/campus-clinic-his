package io.github.ryancamus.clinic.framework.mybatis.handler;

import io.github.ryancamus.clinic.common.money.Money;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@link Money} 与 DECIMAL(12,2) 之间的映射。
 * 通过 mybatis-plus.type-handlers-package 全局注册，实体字段直接声明为 Money 即可。
 */
@MappedTypes(Money.class)
@MappedJdbcTypes(value = JdbcType.DECIMAL, includeNullJdbcType = true)
public class MoneyTypeHandler extends BaseTypeHandler<Money> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Money parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setBigDecimal(i, parameter.amount());
    }

    @Override
    public Money getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toMoney(rs.getBigDecimal(columnName));
    }

    @Override
    public Money getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toMoney(rs.getBigDecimal(columnIndex));
    }

    @Override
    public Money getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toMoney(cs.getBigDecimal(columnIndex));
    }

    private static Money toMoney(BigDecimal value) {
        return value == null ? null : new Money(value);
    }
}
