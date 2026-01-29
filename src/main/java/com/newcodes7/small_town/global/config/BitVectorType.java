package com.newcodes7.small_town.global.config;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.postgresql.util.PGobject;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.BitSet;

/**
 * Hibernate UserType for PostgreSQL bit(n) type
 * Converts between Java BitSet and PostgreSQL bit(1024)
 *
 * Binary quantization: 양수 → 1, 음수 → 0
 */
public class BitVectorType implements UserType<BitSet> {

    private static final int DIMENSION = 1024;

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<BitSet> returnedClass() {
        return BitSet.class;
    }

    @Override
    public boolean equals(BitSet x, BitSet y) throws HibernateException {
        if (x == y) return true;
        if (x == null || y == null) return false;
        return x.equals(y);
    }

    @Override
    public int hashCode(BitSet x) throws HibernateException {
        return x != null ? x.hashCode() : 0;
    }

    @Override
    public BitSet nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        Object value = rs.getObject(position);
        if (value == null) {
            return null;
        }

        String bitString;
        if (value instanceof PGobject) {
            bitString = ((PGobject) value).getValue();
        } else {
            bitString = value.toString();
        }

        if (bitString == null) {
            return null;
        }

        return parseBitString(bitString);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, BitSet value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            PGobject pgObject = new PGobject();
            pgObject.setType("bit");
            pgObject.setValue(toBitString(value));
            st.setObject(index, pgObject);
        }
    }

    @Override
    public BitSet deepCopy(BitSet value) throws HibernateException {
        if (value == null) {
            return null;
        }
        return (BitSet) value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(BitSet value) throws HibernateException {
        return deepCopy(value);
    }

    @Override
    public BitSet assemble(Serializable cached, Object owner) throws HibernateException {
        return deepCopy((BitSet) cached);
    }

    @Override
    public BitSet replace(BitSet detached, BitSet managed, Object owner) throws HibernateException {
        return deepCopy(detached);
    }

    /**
     * BitSet을 bit string으로 변환 (예: "10110...")
     */
    private String toBitString(BitSet bitSet) {
        StringBuilder sb = new StringBuilder(DIMENSION);
        for (int i = 0; i < DIMENSION; i++) {
            sb.append(bitSet.get(i) ? '1' : '0');
        }
        return sb.toString();
    }

    /**
     * bit string을 BitSet으로 변환
     */
    private BitSet parseBitString(String str) {
        BitSet bitSet = new BitSet(DIMENSION);
        for (int i = 0; i < str.length() && i < DIMENSION; i++) {
            if (str.charAt(i) == '1') {
                bitSet.set(i);
            }
        }
        return bitSet;
    }

    /**
     * float 배열을 Binary Quantization하여 BitSet으로 변환
     * 양수 → 1, 음수 → 0
     */
    public static BitSet fromFloatArray(float[] embedding) {
        if (embedding == null) {
            return null;
        }
        BitSet bitSet = new BitSet(embedding.length);
        for (int i = 0; i < embedding.length; i++) {
            if (embedding[i] >= 0) {
                bitSet.set(i);
            }
        }
        return bitSet;
    }

    /**
     * BitSet을 PostgreSQL bit string 포맷으로 변환
     */
    public static String toPostgresBitString(BitSet bitSet, int dimension) {
        if (bitSet == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(dimension);
        for (int i = 0; i < dimension; i++) {
            sb.append(bitSet.get(i) ? '1' : '0');
        }
        return sb.toString();
    }
}
